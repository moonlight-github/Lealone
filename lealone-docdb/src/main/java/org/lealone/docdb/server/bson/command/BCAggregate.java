/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.docdb.server.bson.command;

import java.util.ArrayList;
import java.util.Map.Entry;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.io.ByteBufferBsonInput;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.Utils;
import org.lealone.db.result.Result;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Table;
import org.lealone.docdb.server.DocDBServerConnection;
import org.lealone.docdb.server.DocDBTask;
import org.lealone.sql.PreparedSQLStatement;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.aggregate.Aggregate;
import org.lealone.sql.optimizer.TableFilter;
import org.lealone.sql.query.Select;

public class BCAggregate extends BsonCommand {

    public static BsonDocument execute(ByteBufferBsonInput input, BsonDocument doc,
            DocDBServerConnection conn, DocDBTask task) {
        Table table = findTable(doc, "aggregate", conn);
        if (table != null) {
            aggregate(doc, conn, table, task);
            return null;
        } else {
            return createResponseDocument(doc, 0);
        }
    }

    private static BsonDocument createResponseDocument(BsonDocument doc, int rowCount) {
        BsonDocument document = new BsonDocument();
        BsonDocument cursor = new BsonDocument();
        append(cursor, "id", 0L);
        append(cursor, "ns",
                doc.getString("$db").getValue() + "." + doc.getString("aggregate").getValue());
        BsonArray documents = new BsonArray();
        BsonDocument agg = new BsonDocument();
        append(agg, "_id", 1);
        append(agg, "n", rowCount);
        documents.add(agg);
        cursor.append("firstBatch", documents);
        document.append("cursor", cursor);
        setOk(document);
        return document;
    }

    private static void aggregate(BsonDocument doc, DocDBServerConnection conn, Table table,
            DocDBTask task) {
        ServerSession session = task.session;
        Select select = new Select(session);
        TableFilter tableFilter = new TableFilter(session, table, null, true, select);
        select.addTableFilter(tableFilter, true);
        ArrayList<Expression> selectExpressions = Utils.newSmallArrayList();
        select.setExpressions(selectExpressions);

        BsonArray pipeline = doc.getArray("pipeline", null);
        if (pipeline != null) {
            for (int i = 0, size = pipeline.size(); i < size; i++) {
                BsonDocument document = pipeline.get(i).asDocument();
                for (Entry<String, BsonValue> e : document.entrySet()) {
                    String stage = e.getKey();
                    BsonDocument stageDoc = e.getValue().asDocument();
                    parseStage(stage, stageDoc, select, tableFilter);
                }
            }
        }

        select.init();
        select.prepare();

        PreparedSQLStatement.Yieldable<?> yieldable = select.createYieldableQuery(-1, false, ar -> {
            if (ar.isSucceeded()) {
                Result result = ar.getResult();
                result.next();
                task.conn.sendResponse(task.requestId,
                        createResponseDocument(task.doc, result.currentRow()[0].getInt()));
            } else {
                task.conn.sendError(task.session, task.requestId, ar.getCause());
            }
        });
        task.si.submitYieldableCommand(task.requestId, yieldable);
    }

    private static void parseStage(String stage, BsonDocument stageDoc, Select select,
            TableFilter tableFilter) {
        switch (stage) {
        case "$group":
            group(stageDoc, select, tableFilter);
            break;
        case "$match":
            if (select.isGroupQuery())
                having(stageDoc, select, tableFilter);
            else
                where(stageDoc, select, tableFilter);
            break;
        case "$sort":
            sort(stageDoc, select, tableFilter);
            break;
        default:
            throw DbException.getUnsupportedException("aggregation pipeline stage " + stage);
        }
    }

    private static void parseAccumulator(BsonDocument fieldDoc, Select select, TableFilter tableFilter) {
        String accumulator = fieldDoc.getFirstKey();
        BsonValue accumulatorValue = fieldDoc.get(accumulator);
        switch (accumulator) {
        case "$sum": {
            if (accumulatorValue.isString()) {
                toAggregate(accumulatorValue, select, tableFilter, Aggregate.SUM);
            } else {
                Expression a = Aggregate.create(Aggregate.COUNT_ALL, null, select, false);
                select.getExpressions().add(a);
                select.setGroupQuery();
            }
            break;
        }
        case "$avg": {
            toAggregate(accumulatorValue, select, tableFilter, Aggregate.AVG);
            break;
        }
        case "$min": {
            toAggregate(accumulatorValue, select, tableFilter, Aggregate.MIN);
            break;
        }
        case "$max": {
            toAggregate(accumulatorValue, select, tableFilter, Aggregate.MAX);
            break;
        }
        case "$stdDevPop": {
            toAggregate(accumulatorValue, select, tableFilter, Aggregate.STDDEV_POP);
            break;
        }
        case "$stdDevSamp": {
            toAggregate(accumulatorValue, select, tableFilter, Aggregate.STDDEV_SAMP);
            break;
        }
        default:
            throw DbException.getUnsupportedException("accumulator " + accumulator);
        }
    }

    private static void toAggregate(BsonValue accumulatorValue, Select select, TableFilter tableFilter,
            int type) {
        String f = accumulatorValue.asString().getValue();
        if (f.charAt(0) == '$')
            f = f.substring(1);
        Expression on = getExpressionColumn(tableFilter, f.toUpperCase());
        Expression a = Aggregate.create(type, on, select, false);
        select.getExpressions().add(a);
        select.setGroupQuery();
    }

    private static void group(BsonDocument doc, Select select, TableFilter tableFilter) {
        if (!doc.containsKey("_id"))
            throw DbException.getUnsupportedException("a group specification must include an _id");

        for (Entry<String, BsonValue> e : doc.entrySet()) {
            String k = e.getKey();
            BsonValue v = e.getValue();

            if (k.equals("_id")) {
                if (v.isString()) {
                    String id = v.asString().getValue();
                    if (id.charAt(0) == '$')
                        id = id.substring(1);
                    ArrayList<Expression> group = Utils.newSmallArrayList();
                    group.add(getExpressionColumn(tableFilter, id.toUpperCase()));
                }
            } else {
                parseAccumulator(v.asDocument(), select, tableFilter);
            }
        }
    }

    private static void having(BsonDocument doc, Select select, TableFilter tableFilter) {
        select.setHaving(toWhereCondition(doc, tableFilter, select.getSession()));
    }

    private static void where(BsonDocument doc, Select select, TableFilter tableFilter) {
        select.addCondition(toWhereCondition(doc, tableFilter, select.getSession()));
    }

    private static void sort(BsonDocument doc, Select select, TableFilter tableFilter) {
    }
}
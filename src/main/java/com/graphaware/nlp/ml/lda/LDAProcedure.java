/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.graphaware.nlp.ml.lda;

import com.graphaware.nlp.domain.AnnotatedText;
import com.graphaware.nlp.domain.Tag;
import com.graphaware.nlp.procedure.NLPProcedure;
import com.graphaware.nlp.processor.TextProcessor;
import com.graphaware.nlp.processor.TextProcessorsManager;
//import com.graphaware.spark.ml.lda.LDAProcessor;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.collection.RawIterator;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.helpers.collection.Iterators;
import org.neo4j.kernel.api.exceptions.ProcedureException;
import org.neo4j.kernel.api.proc.CallableProcedure;
import org.neo4j.kernel.api.proc.Neo4jTypes;
import org.neo4j.kernel.api.proc.ProcedureSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import static org.neo4j.kernel.api.proc.ProcedureSignature.procedureSignature;

public class LDAProcedure extends NLPProcedure {

    private static final Logger LOG = LoggerFactory.getLogger(LDAProcedure.class);

    private final GraphDatabaseService database;
    private final TextProcessorsManager processorManager;
    private final TextProcessor textProcessor;

    private final static int PARAMETER_DEFAULT_GROUPS = 5;
    private final static int PARAMETER_DEFAULT_ITERATIONS = 20;
    private final static int PARAMETER_DEFAULT_TOPICS = 10;
    private final static boolean PARAMETER_DEFAULT_STORE = true;

    private final static String PARAMETER_NAME_GROUPS = "clusters";
    private final static String PARAMETER_NAME_ITERATIONS = "iterations";
    private final static String PARAMETER_NAME_TOPICS = "topics";
    private final static String PARAMETER_NAME_STORE = "store";
    private final static String PARAMETER_NAME_TEXT = "text";

    private static final String PARAMETER_NAME_OUTPUT_TOPIC = "topic";
    private static final String PARAMETER_NAME_OUTPUT_VALUE = "value";

    public LDAProcedure(GraphDatabaseService database, TextProcessorsManager processorManager) {
        this.database = database;
        this.processorManager = processorManager;
        this.textProcessor = processorManager.getDefaultProcessor();
    }

    public CallableProcedure.BasicProcedure lda() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("ml", "lda"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_INPUT_OUTPUT, Neo4jTypes.NTInteger).build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                Integer numberOfTopicGroups = (Integer) inputParams.getOrDefault(PARAMETER_NAME_GROUPS, PARAMETER_DEFAULT_GROUPS);
                Integer maxIterations = (Integer) inputParams.getOrDefault(PARAMETER_NAME_ITERATIONS, PARAMETER_DEFAULT_ITERATIONS);
                Integer numberOfTopics = (Integer) inputParams.getOrDefault(PARAMETER_NAME_TOPICS, PARAMETER_DEFAULT_TOPICS);
                Boolean storeModel = (Boolean) inputParams.getOrDefault(PARAMETER_NAME_STORE, PARAMETER_DEFAULT_STORE);

                try {
                    LOG.warn("Start extracting topic");
//                    Tuple2<Object, Tuple2<String, Object>[]>[] topics = LDAProcessor.extract("MATCH (n:AnnotatedText) "
//                            + "MATCH (n)-[:CONTAINS_SENTENCE]->(s:Sentence)-[r:HAS_TAG]->(t:Tag) "
//                            + "WHERE length(t.value) > 5 "
//                            + "return id(n) as docId, sum(r.tf) as tf, t.value as word", numberOfTopicGroups, maxIterations, numberOfTopics, storeModel);
//                    storeTopics(topics);
                    LOG.warn("Completed extracting topic");
                    return Iterators.asRawIterator(Collections.<Object[]>singleton(new Object[]{
                        1
                    }).iterator());
                } catch (Exception ex) {
                    LOG.error("Error while annotating", ex);
                    throw new RuntimeException(ex);
                }
            }

            private void storeTopics(Tuple2<Object, Tuple2<String, Object>[]>[] topicsAssociation) {
                for (Tuple2<Object, Tuple2<String, Object>[]> document : topicsAssociation) {
                    long docId = (Long)document._1;
                    Map<String, Object> param = new HashMap<>();
                    param.put("docId", docId);
                    database.execute("MATCH (a:AnnotatedText) "
                            + "WHERE id(a) = {docId} "
                            + "MATCH (a)<-[d:DESCRIBES]-() "
                            + "DELETE d ",
                            param);
                    Tuple2<String, Object>[] topics = document._2;
                    for (Tuple2<String, Object> topic : topics) {
                        Map<String, Object> internalParam = new HashMap<>();
                        internalParam.put("docId", docId);
                        internalParam.put("topic", topic._1);
                        internalParam.put("value", topic._2);
                        database.execute("MATCH (t:Tag) "
                                + "MATCH (a:AnnotatedText) "
                                + "WHERE t.value = {topic} AND id(a) = {docId} "
                                + "MERGE (a)<-[:DESCRIBES {value: {value}}]-(t) ",
                                internalParam);
                    }
                }
            }
        };
    }

    public CallableProcedure.BasicProcedure topicDistribution() {
        return new CallableProcedure.BasicProcedure(procedureSignature(getProcedureName("ml", "topic"))
                .mode(ProcedureSignature.Mode.READ_WRITE)
                .in(PARAMETER_NAME_INPUT, Neo4jTypes.NTMap)
                .out(PARAMETER_NAME_OUTPUT_TOPIC, Neo4jTypes.NTString)
                .out(PARAMETER_NAME_OUTPUT_VALUE, Neo4jTypes.NTFloat)
                .build()) {

            @Override
            public RawIterator<Object[], ProcedureException> apply(Context ctx, Object[] input) throws ProcedureException {
                checkIsMap(input[0]);
                Map<String, Object> inputParams = (Map) input[0];
                String text = (String) inputParams.get(PARAMETER_NAME_TEXT);
                if (text == null) {
                    throw new RuntimeException("Missing parameter " + PARAMETER_NAME_TEXT);
                }
                AnnotatedText annotateText = textProcessor.annotateText(text, 0, 0, false);
                List<Tag> tags = annotateText.getTags();
                Tuple2<String, Object>[] tagsArray = new Tuple2[tags.size()];
                for (int i = 0; i < tags.size(); i++) {
                    Tag tag = tags.get(i);
                    tagsArray[i] = new Tuple2<>(tag.getLemma(), tag.getMultiplicity());
                }
                try {
                    LOG.warn("Start extracting topic");
//                    Tuple2<String, Object>[] topics = LDAProcessor.predictTopics(tagsArray);
//                    LOG.warn("Completed extracting topic: " + topics);
                    Set<Object[]> result = new HashSet<>();
//                    for (int i = 0; i < topics.length; i++) {
//                        result.add(new Object[]{topics[i]._1, ((Double) topics[i]._2).floatValue()});
//                    }
                    return Iterators.asRawIterator(result.iterator());
                } catch (Exception ex) {
                    LOG.error("Error while annotating", ex);
                    throw new RuntimeException(ex);
                }
            }
        };
    }

}

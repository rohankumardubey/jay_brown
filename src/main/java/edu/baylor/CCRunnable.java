package edu.baylor;

import edu.baylor.schema.Labels;
import edu.baylor.schema.RelationshipTypes;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.traversal.*;
import org.neo4j.logging.Log;
import org.roaringbitmap.longlong.LongConsumer;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import java.util.*;

import static edu.baylor.Procedures.infected;
import static edu.baylor.schema.Properties.NAME;
import static edu.baylor.schema.Properties.TIME;
import static edu.baylor.schema.Properties.RECORD_NUM;

public class CCRunnable implements Runnable {

    private GraphDatabaseService db;
    private Log log;
    private Long time;
    private Long finalEndTime;
    private ArrayList<String> stringsToPrint;

    public CCRunnable(GraphDatabaseService db, Log log, Long time, Long finalEndTime, ArrayList<String> stringsToPrint)  {
        this.db = db;
        this.log = log;
        this.time = time;
        this.finalEndTime = finalEndTime;
        this.stringsToPrint = stringsToPrint;
    }

    @Override
    public void run() {
        Roaring64NavigableMap nextPatients = new Roaring64NavigableMap();
        Roaring64NavigableMap infectedPatients = new Roaring64NavigableMap();

        // Step 1: Get the initial set of infected patients
        try(Transaction tx = db.beginTx()) {
            ResourceIterator<Node> infectedNodes =  db.findNodes(Labels.Infected);
            while (infectedNodes.hasNext()) {
                Node patient = infectedNodes.next();
                long infectedTime = getTimeOfCreation(patient);
                // Skip any infected nodes beyond our final end time
                if (infectedTime > finalEndTime) { continue; };
                infectedPatients.add(patient.getId());
            }
            tx.success();
        }

        Iterator<Long> iterator;
        long nodeId;

        Transaction tx = db.beginTx();
        try {
                TimeSlicedExpander expander = new TimeSlicedExpander(time, finalEndTime, log);

                TraversalDescription td = db.traversalDescription()
                        .breadthFirst()
                        .expand(expander)
                        .evaluator(Evaluators.excludeStartPosition())
                        .uniqueness(Uniqueness.RELATIONSHIP_GLOBAL);

                // Add already infected patients to seen
                infected.or(infectedPatients);

                iterator = infectedPatients.iterator();
                while (iterator.hasNext()) {
                    nodeId = iterator.next();
                    Node patient = db.getNodeById(nodeId);

                    for (Path p : td.traverse(patient)) {
                        if (p.endNode().hasLabel(Labels.PATIENT)) {
                            infected.add(p.endNode().getId());
                            nextPatients.add(p.endNode().getId());
                        }
                    }
                }

                infected.or(nextPatients);
                nextPatients.andNot(infectedPatients);

                stringsToPrint.add("/" + " : " + "From: " + time + " Until: " + finalEndTime
                        + " Infected:  at start " + infectedPatients.getLongCardinality()
                        + " newly infected " + nextPatients.getLongCardinality() + " All Infected: "
                        + infected.getLongCardinality() + ";\n");

            tx.success();
        } catch ( Exception e ) {
            tx.failure();
            this.stringsToPrint.add("There was an exception: " + e.getMessage());
        } finally {
            tx.close();
        }
    }

    private long getTimeOfCreation(Node patient) {
        long currCreationTime = Long.MAX_VALUE;
        for (Relationship r : patient.getRelationships(RelationshipTypes.OUTPUT)) {
            long retrievedTime = ((Number) r.getProperty(TIME)).longValue();
            currCreationTime = Math.min(retrievedTime, currCreationTime);
        }

        for (Relationship r : patient.getRelationships(RelationshipTypes.CARRIER)) {
            long parent_time = ((Number) r.getStartNode().getProperty(RECORD_NUM)).longValue();
            if (parent_time < currCreationTime) {
                currCreationTime = parent_time;
            }
        }
        return currCreationTime;
    }
}
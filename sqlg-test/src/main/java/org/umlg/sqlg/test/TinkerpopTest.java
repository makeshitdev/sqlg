package org.umlg.sqlg.test;

import org.apache.tinkerpop.gremlin.AbstractGremlinTest;
import org.apache.tinkerpop.gremlin.FeatureRequirementSet;
import org.apache.tinkerpop.gremlin.LoadGraphWith;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.engine.StandardTraversalEngine;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.PartitionStrategy;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.structure.io.GraphReader;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoIo;
import org.apache.tinkerpop.gremlin.structure.io.gryo.GryoReader;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedEdge;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedFactory;
import org.apache.tinkerpop.gremlin.structure.util.detached.DetachedVertex;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;
import org.junit.Assert;
import org.junit.Test;
import org.umlg.sqlg.structure.SqlgGraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Date: 2014/07/13
 * Time: 6:32 PM
 */
public class TinkerpopTest extends BaseTest {

    private static final String partition = "gremlin.partitionGraphStrategy.partition";

    @Test
    public void shouldWriteToMultiplePartitions() {
        final PartitionStrategy partitionStrategyAA = PartitionStrategy.build()
                .partitionKey(partition).writePartition("A").addReadPartition("A").create();
        final GraphTraversalSource sourceAA = create(sqlgGraph, partitionStrategyAA);

        final PartitionStrategy partitionStrategyBA = PartitionStrategy.build()
                .partitionKey(partition).writePartition("B").addReadPartition("A").create();
        final GraphTraversalSource sourceBA = create(sqlgGraph, partitionStrategyBA);

        final PartitionStrategy partitionStrategyCAB = PartitionStrategy.build()
                .partitionKey(partition).writePartition("C").addReadPartition("A").addReadPartition("B").create();
        final GraphTraversalSource sourceCAB = create(sqlgGraph, partitionStrategyCAB);

        final PartitionStrategy partitionStrategyC = PartitionStrategy.build()
                .partitionKey(partition).writePartition("C").create();
        final GraphTraversalSource sourceC = create(sqlgGraph, partitionStrategyC);

        final PartitionStrategy partitionStrategyCA = PartitionStrategy.build()
                .partitionKey(partition).writePartition("C").addReadPartition("A").create();
        final GraphTraversalSource sourceCA = create(sqlgGraph, partitionStrategyCA);

        final PartitionStrategy partitionStrategyCABC = PartitionStrategy.build()
                .partitionKey(partition)
                .writePartition("C")
                .addReadPartition("A")
                .addReadPartition("B")
                .addReadPartition("C").create();
        final GraphTraversalSource sourceCABC = create(sqlgGraph, partitionStrategyCABC);

        final PartitionStrategy partitionStrategyCC = PartitionStrategy.build()
                .partitionKey(partition).writePartition("C").addReadPartition("C").create();
        final GraphTraversalSource sourceCC = create(sqlgGraph, partitionStrategyCC);

        final PartitionStrategy partitionStrategyCBC = PartitionStrategy.build()
                .partitionKey(partition).writePartition("C").addReadPartition("C").addReadPartition("B").create();
        final GraphTraversalSource sourceCBC = create(sqlgGraph, partitionStrategyCBC);

        final Vertex vA = sourceAA.addV("any", "a").next();
        final Vertex vAA = sourceAA.addV("any", "aa").next();
        final Edge eAtoAA = sourceAA.withSideEffect("vAA", vAA).V(vA.id()).addOutE("a->a", "vAA").next();

        final Vertex vB = sourceBA.addV("any", "b").next();
        sourceBA.withSideEffect("vB", vB).V(vA.id()).addOutE("a->b", "vB").next();

        final Vertex vC = sourceCAB.addV("any", "c").next();
        final Edge eBtovC = sourceCAB.withSideEffect("vC", vC).V(vB.id()).addOutE("b->c", "vC").next();
        final Edge eAtovC = sourceCAB.withSideEffect("vC", vC).V(vA.id()).addOutE("a->c", "vC").next();


        // excluded vertices; vA is not in {B,C}
        Long count = sourceCBC.V(vC.id()).inE().count().next();
        this.sqlgGraph.tx().commit();

        assertEquals(2l, count, 0);
        assertEquals(new Long(1), sourceCBC.V(vC.id()).in().count().next());
        assertEquals(vC.id(), sourceCBC.E(eBtovC.id()).inV().id().next());
        assertEquals(vB.id(), sourceCBC.E(eBtovC.id()).outV().id().next());
        assertEquals(vC.id(), sourceCBC.E(eAtovC.id()).inV().id().next());
        Assert.assertFalse(sourceCBC.E(eAtovC.id()).outV().hasNext());
    }

    private GraphTraversalSource create(SqlgGraph sqlgGraph, final PartitionStrategy strategy) {
        final GraphTraversalSource.Builder builder = GraphTraversalSource.build().engine(StandardTraversalEngine.build());
        Stream.of(strategy).forEach(builder::with);
        return builder.create(sqlgGraph);
    }

//    @Test
//    public void g_VX1X_repeatXbothEXcreatedX_whereXwithoutXeXX_aggregateXeX_otherVX_emit_path() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        assertModernGraph(graph, true, false);
//        GraphTraversalSource g = graph.traversal();
//        final Traversal<Vertex, Path> traversal = g.V(convertToVertexId("marko")).repeat(__.bothE("created").where(P.without("e")).aggregate("e").otherV()).emit().path();
////        final Traversal<Vertex, Path> traversal = g.V(convertToVertexId("marko")).repeat(__.bothE("created").aggregate("e").otherV().where(P.without("e"))).emit().path();
//        printTraversalForm(traversal);
//        final List<Path> paths = traversal.toList();
//        assertEquals(4, paths.size());
//        assertEquals(1, paths.stream().filter(path -> path.size() == 3).count());
//        assertEquals(2, paths.stream().filter(path -> path.size() == 5).count());
//        assertEquals(1, paths.stream().filter(path -> path.size() == 7).count());
//        Assert.assertFalse(traversal.hasNext());
//    }
//
//    @Test
//    public void g_EX7X_hasXlabelXknowsX() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        assertModernGraph(graph, true, false);
//        GraphTraversalSource g = graph.traversal();
//
//        Object o = convertToEdgeId(graph, "marko", "knows", "vadas");
//        final Traversal<Edge, Edge> traversal =  g.E(o).hasLabel("knows");
//        printTraversalForm(traversal);
//        int counter = 0;
//        while (traversal.hasNext()) {
//            counter++;
//            assertEquals("knows", traversal.next().label());
//        }
//        assertEquals(1, counter);
//    }
//
//    @Test
//    public void g_VX1X_outXcreatedX_inXcreatedX_simplePath() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        assertModernGraph(graph, true, false);
//        GraphTraversalSource g = graph.traversal();
//
//        final Traversal<Vertex, Vertex> traversal =  g.V(convertToVertexId("marko")).out("created").in("created").simplePath();
//        printTraversalForm(traversal);
//        int counter = 0;
//        while (traversal.hasNext()) {
//            counter++;
//            Vertex vertex = traversal.next();
//            Assert.assertTrue(vertex.value("name").equals("josh") || vertex.value("name").equals("peter"));
//        }
//        assertEquals(2, counter);
//        Assert.assertFalse(traversal.hasNext());
//    }
//
//    @Test
//    public void g_VX1X_outXcreatedX_inXcreatedX_cyclicPath() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        assertModernGraph(graph, true, false);
//        GraphTraversalSource g = graph.traversal();
//        final Traversal<Vertex, Vertex> traversal =  g.V(convertToVertexId("marko")).out("created").in("created").cyclicPath();
//        printTraversalForm(traversal);
//        int counter = 0;
//        while (traversal.hasNext()) {
//            counter++;
//            Vertex vertex = traversal.next();
//            assertEquals("marko", vertex.<String>value("name"));
//        }
//        Assert.assertEquals(1, counter);
//        Assert.assertFalse(traversal.hasNext());
//    }
//
//    @Test
//    public void shouldLoadVerticesViaIds() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        assertModernGraph(graph, true, false);
//        GraphTraversalSource g = graph.traversal();
//        final List<Vertex> vertices = g.V().toList();
//        final List<Object> ids = vertices.stream().map(Vertex::id).collect(Collectors.toList());
//        final List<Vertex> verticesReloaded = g.V(ids.toArray()).toList();
//        assertEquals(vertices.size(), verticesReloaded.size());
//        assertEquals(new HashSet<>(vertices), new HashSet<>(verticesReloaded));
//    }
//
////    @Test
//    public void g_V_both_both_count() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/grateful-dead.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        final Traversal<Vertex, Long> traversal = graph.traversal().V().both().both().count();
//        printTraversalForm(traversal);
//        assertEquals(new Long(1406914), traversal.next());
//        Assert.assertFalse(traversal.hasNext());
//    }
//
////    @Test
//    public void shouldConstructDetachedEdge() throws IOException {
//        Graph graph = this.sqlgGraph;
//        final GraphReader reader = GryoReader.build()
//                .mapper(graph.io(GryoIo.build()).mapper().create())
//                .create();
//        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.kryo")) {
//            reader.readGraph(stream, graph);
//        }
//        assertModernGraph(graph, true, false);
//        GraphTraversalSource g = graph.traversal();
//        g.E(convertToEdgeId(graph, "marko", "knows", "vadas")).next().property("year", 2002);
//        final DetachedEdge detachedEdge = DetachedFactory.detach(g.E(convertToEdgeId(graph, "marko", "knows", "vadas")).next(), true);
//        assertEquals(convertToEdgeId(graph, "marko", "knows", "vadas"), detachedEdge.id());
//        assertEquals("knows", detachedEdge.label());
//        assertEquals(DetachedVertex.class, detachedEdge.vertices(Direction.OUT).next().getClass());
//        assertEquals(convertToVertexId("marko"), detachedEdge.vertices(Direction.OUT).next().id());
//        assertEquals("person", detachedEdge.vertices(Direction.IN).next().label());
//        assertEquals(DetachedVertex.class, detachedEdge.vertices(Direction.IN).next().getClass());
//        assertEquals(convertToVertexId("vadas"), detachedEdge.vertices(Direction.IN).next().id());
//        assertEquals("person", detachedEdge.vertices(Direction.IN).next().label());
//
//        assertEquals(2, IteratorUtils.count(detachedEdge.properties()));
//        assertEquals(1, IteratorUtils.count(detachedEdge.properties("year")));
//        assertEquals(0.5d, detachedEdge.properties("weight").next().value());
//    }
//
//    public Object convertToEdgeId(final Graph graph, final String outVertexName, String edgeLabel, final String inVertexName) {
//        return graph.traversal().V().has("name", outVertexName).outE(edgeLabel).as("e").inV().has("name", inVertexName).<Edge>select("e").next().id();
//    }
//
//
//    public Object convertToVertexId(final String vertexName) {
//        return convertToVertexId(this.sqlgGraph, vertexName);
//    }
//
//    /**
//     * Looks up the identifier as generated by the current source graph being tested.
//     *
//     * @param g          the graph to get the element id from
//     * @param vertexName a unique string that will identify a graph element within a graph
//     * @return the id as generated by the graph
//     */
//    public Object convertToVertexId(final Graph g, final String vertexName) {
//        return convertToVertex(g, vertexName).id();
//    }
//
//    public Vertex convertToVertex(final Graph g, final String vertexName) {
//        // all test graphs have "name" as a unique id which makes it easy to hardcode this...works for now
//        return (g.traversal().V().has("name", vertexName).next());
//    }
//
//    public static void assertModernGraph(final Graph g1, final boolean assertDouble, final boolean lossyForId) {
//        assertToyGraph(g1, assertDouble, lossyForId, true);
//    }
//
//    private static void assertToyGraph(final Graph g1, final boolean assertDouble, final boolean lossyForId, final boolean assertSpecificLabel) {
//        assertEquals(new Long(6), g1.traversal().V().count().next());
//        assertEquals(new Long(6), g1.traversal().E().count().next());
//
//        final Vertex v1 = (Vertex) g1.traversal().V().has("name", "marko").next();
//        assertEquals(29, v1.<Integer>value("age").intValue());
//        assertEquals(2, v1.keys().size());
//        assertEquals(assertSpecificLabel ? "person" : Vertex.DEFAULT_LABEL, v1.label());
//        assertId(g1, lossyForId, v1, 1);
//
//        final List<Edge> v1Edges = g1.traversal().V(v1.id()).bothE().toList();
//        assertEquals(3, v1Edges.size());
//        v1Edges.forEach(e -> {
//            if (g1.traversal().E(e.id()).inV().values("name").next().equals("vadas")) {
//                assertEquals("knows", e.label());
//                if (assertDouble)
//                    assertEquals(0.5d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.5f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 7);
//            } else if (g1.traversal().E(e.id()).inV().values("name").next().equals("josh")) {
//                assertEquals("knows", e.label());
//                if (assertDouble)
//                    assertEquals(1.0, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(1.0f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 8);
//            } else if (g1.traversal().E(e.id()).inV().values("name").next().equals("lop")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(0.4d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.4f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 9);
//            } else {
//                fail("Edge not expected");
//            }
//        });
//
//        final Vertex v2 = (Vertex) g1.traversal().V().has("name", "vadas").next();
//        assertEquals(27, v2.<Integer>value("age").intValue());
//        assertEquals(2, v2.keys().size());
//        assertEquals(assertSpecificLabel ? "person" : Vertex.DEFAULT_LABEL, v2.label());
//        assertId(g1, lossyForId, v2, 2);
//
//        final List<Edge> v2Edges = g1.traversal().V(v2.id()).bothE().toList();
//        assertEquals(1, v2Edges.size());
//        v2Edges.forEach(e -> {
//            if (g1.traversal().E(e.id()).outV().values("name").next().equals("marko")) {
//                assertEquals("knows", e.label());
//                if (assertDouble)
//                    assertEquals(0.5d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.5f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 7);
//            } else {
//                fail("Edge not expected");
//            }
//        });
//
//        final Vertex v3 = (Vertex) g1.traversal().V().has("name", "lop").next();
//        assertEquals("java", v3.<String>value("lang"));
//        assertEquals(2, v2.keys().size());
//        assertEquals(assertSpecificLabel ? "software" : Vertex.DEFAULT_LABEL, v3.label());
//        assertId(g1, lossyForId, v3, 3);
//
//        final List<Edge> v3Edges = g1.traversal().V(v3.id()).bothE().toList();
//        assertEquals(3, v3Edges.size());
//        v3Edges.forEach(e -> {
//            if (g1.traversal().E(e.id()).outV().values("name").next().equals("peter")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(0.2d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.2f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 12);
//            } else if (g1.traversal().E(e.id()).outV().next().value("name").equals("josh")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(0.4d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.4f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 11);
//            } else if (g1.traversal().E(e.id()).outV().values("name").next().equals("marko")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(0.4d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.4f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 9);
//            } else {
//                fail("Edge not expected");
//            }
//        });
//
//        final Vertex v4 = (Vertex) g1.traversal().V().has("name", "josh").next();
//        assertEquals(32, v4.<Integer>value("age").intValue());
//        assertEquals(2, v4.keys().size());
//        assertEquals(assertSpecificLabel ? "person" : Vertex.DEFAULT_LABEL, v4.label());
//        assertId(g1, lossyForId, v4, 4);
//
//        final List<Edge> v4Edges = g1.traversal().V(v4.id()).bothE().toList();
//        assertEquals(3, v4Edges.size());
//        v4Edges.forEach(e -> {
//            if (e.inVertex().values("name").next().equals("ripple")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(1.0d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(1.0f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 10);
//            } else if (e.inVertex().values("name").next().equals("lop")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(0.4d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.4f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 11);
//            } else if (e.outVertex().values("name").next().equals("marko")) {
//                assertEquals("knows", e.label());
//                if (assertDouble)
//                    assertEquals(1.0d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(1.0f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 8);
//            } else {
//                fail("Edge not expected");
//            }
//        });
//
//        final Vertex v5 = (Vertex) g1.traversal().V().has("name", "ripple").next();
//        assertEquals("java", v5.<String>value("lang"));
//        assertEquals(2, v5.keys().size());
//        assertEquals(assertSpecificLabel ? "software" : Vertex.DEFAULT_LABEL, v5.label());
//        assertId(g1, lossyForId, v5, 5);
//
//        final List<Edge> v5Edges = IteratorUtils.list(v5.edges(Direction.BOTH));
//        assertEquals(1, v5Edges.size());
//        v5Edges.forEach(e -> {
//            if (e.outVertex().values("name").next().equals("josh")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(1.0d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(1.0f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 10);
//            } else {
//                fail("Edge not expected");
//            }
//        });
//
//        final Vertex v6 = (Vertex) g1.traversal().V().has("name", "peter").next();
//        assertEquals(35, v6.<Integer>value("age").intValue());
//        assertEquals(2, v6.keys().size());
//        assertEquals(assertSpecificLabel ? "person" : Vertex.DEFAULT_LABEL, v6.label());
//        assertId(g1, lossyForId, v6, 6);
//
//        final List<Edge> v6Edges = IteratorUtils.list(v6.edges(Direction.BOTH));
//        assertEquals(1, v6Edges.size());
//        v6Edges.forEach(e -> {
//            if (e.inVertex().values("name").next().equals("lop")) {
//                assertEquals("created", e.label());
//                if (assertDouble)
//                    assertEquals(0.2d, e.value("weight"), 0.0001d);
//                else
//                    assertEquals(0.2f, e.value("weight"), 0.0001f);
//                assertEquals(1, e.keys().size());
//                assertId(g1, lossyForId, e, 12);
//            } else {
//                fail("Edge not expected");
//            }
//        });
//    }
//
//    private static void assertId(final Graph g, final boolean lossyForId, final Element e, final Object expected) {
//        if (g.features().edge().supportsUserSuppliedIds()) {
//            if (lossyForId)
//                assertEquals(expected.toString(), e.id().toString());
//            else
//                assertEquals(expected, e.id());
//        }
//    }
//
//    //    //    @Test
////    @LoadGraphWith(MODERN)
////    public void g_V_hasXageX_propertiesXname_ageX_value() throws IOException {
////        Graph g = this.sqlgGraph;
////        final GraphReader reader = KryoReader.build().workingDirectory(File.separator + "tmp").create();
////        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.gio")) {
////            reader.readGraph(stream, g);
////        }
////
//////        Traversal<Vertex, Object> gt = get_g_V_hasXageX_propertiesXage_nameX_value();
//////        gt.toList().forEach(a-> System.out.println(a));
////        System.out.println("------------");
////        Traversal<Vertex, Object>gt = get_g_V_hasXageX_propertiesXname_ageX_value();
////        gt.toList().forEach(a-> System.out.println(a));
////
//////        Arrays.asList(/*get_g_V_hasXageX_propertiesXage_nameX_value(),*/ get_g_V_hasXageX_propertiesXname_ageX_value()).forEach(traversal -> {
//////            printTraversalForm(traversal);
//////            checkResults(Arrays.asList("marko", 29, "vadas", 27, "josh", 32, "peter", 35), traversal);
//////        });
////    }
////
////    public <T> void checkResults(final List<T> expectedResults, final Traversal<?, T> traversal) {
////        final List<T> results = traversal.toList();
////        assertEquals("Checking result size", expectedResults.size(), results.size());
////        for (T t : results) {
////            if (t instanceof Map) {
////                assertTrue("Checking map result existence: " + t, expectedResults.stream().filter(e -> e instanceof Map).filter(e -> checkMap((Map) e, (Map) t)).findAny().isPresent());
////            } else {
////                assertTrue("Checking result existence: " + t, expectedResults.contains(t));
////            }
////        }
////        final Map<T, Long> expectedResultsCount = new HashMap<>();
////        final Map<T, Long> resultsCount = new HashMap<>();
////        assertEquals("Checking indexing is equivalent", expectedResultsCount.size(), resultsCount.size());
////        expectedResults.forEach(t -> MapHelper.incr(expectedResultsCount, t, 1l));
////        results.forEach(t -> MapHelper.incr(resultsCount, t, 1l));
////        expectedResultsCount.forEach((k, v) -> assertEquals("Checking result group counts", v, resultsCount.get(k)));
////        assertFalse(traversal.hasNext());
////    }
////
////    private <A, B> boolean checkMap(final Map<A, B> expectedMap, final Map<A, B> actualMap) {
////        final List<Map.Entry<A, B>> actualList = actualMap.entrySet().stream().sorted((a, b) -> a.getKey().toString().compareTo(b.getKey().toString())).collect(Collectors.toList());
////        final List<Map.Entry<A, B>> expectedList = expectedMap.entrySet().stream().sorted((a, b) -> a.getKey().toString().compareTo(b.getKey().toString())).collect(Collectors.toList());
////
////        if (expectedList.size() > actualList.size()) {
////            return false;
////        } else if (actualList.size() > expectedList.size()) {
////            return false;
////        }
////
////        for (int i = 0; i < actualList.size(); i++) {
////            if (!actualList.get(i).getKey().equals(expectedList.get(i).getKey())) {
////                return false;
////            }
////            if (!actualList.get(i).getValue().equals(expectedList.get(i).getValue())) {
////                return false;
////            }
////        }
////        return true;
////    }
////
////    public Traversal<Vertex, Object> get_g_V_hasXageX_propertiesXname_ageX_value() {
////        return this.sqlgGraph.traversal().V().has("age").properties("name", "age").value();
////    }
////
////    public Traversal<Vertex, Object> get_g_V_hasXageX_propertiesXage_nameX_value() {
////        return this.sqlgGraph.traversal().V().has("age").properties("age", "name").value();
////    }
////
////    //    @Test
////    @FeatureRequirementSet(FeatureRequirementSet.Package.VERTICES_ONLY)
////    public void shouldNotReHideAnAlreadyHiddenKeyWhenGettingHiddenValue() {
////        final Vertex v = this.sqlgGraph.addVertex("name", "marko", Graph.Key.hide("acl"), "rw", Graph.Key.hide("other"), "rw");
////        this.sqlgGraph.tx().commit();
////        final Vertex v1 = this.sqlgGraph.v(v.id());
////        v1.hiddenKeys().stream().forEach(hiddenKey -> assertTrue(v1.hiddenValues(hiddenKey).hasNext()));
////        assertFalse(v1.hiddenValues(Graph.Key.hide("other")).hasNext());
////        assertTrue(v1.hiddenValues("other").hasNext());
////
////        final Vertex u = this.sqlgGraph.addVertex();
////        Edge e = v1.addEdge("knows", u, Graph.Key.hide("acl"), "private", "acl", "public");
////        this.sqlgGraph.tx().commit();
////        final Edge e1 = this.sqlgGraph.e(e.id());
////        e1.hiddenKeys().stream().forEach(hiddenKey -> assertTrue(e1.hiddenValues(hiddenKey).hasNext()));
////        assertFalse(e1.hiddenValues(Graph.Key.hide("acl")).hasNext());
////        assertTrue(e1.hiddenValues("acl").hasNext());
////        assertEquals("public", e1.iterators().propertyIterator("acl").next().value());
////    }
////
////    //    @Test
////    public void shouldHandleHiddenVertexProperties() {
////
////        final Vertex v = this.sqlgGraph.addVertex(Graph.Key.hide("age"), 29, "age", 16, "name", "marko", "food", "taco", Graph.Key.hide("color"), "purple");
////        this.sqlgGraph.tx().commit();
////
////        boolean multi = false;
////
////        assertTrue(v.property("age").isPresent());
////        assertTrue(v.value("age").equals(16));
////        assertTrue(v.properties("age").count().next().intValue() == 1);
////        assertTrue(v.properties("age").value().next().equals(16));
////        assertTrue(v.hiddens("age").count().next().intValue() == (multi ? 2 : 1));
////        assertTrue(v.hiddens(Graph.Key.hide("age")).count().next().intValue() == 0);
////        assertTrue(v.properties(Graph.Key.hide("age")).count().next().intValue() == 0);
////        assertTrue(multi ? v.hiddens("age").value().toList().contains(34) : v.hiddens("age").value().toList().contains(29));
////        assertTrue(v.hiddens("age").value().toList().contains(29));
////        assertTrue(v.hiddenKeys().size() == 2);
////        assertTrue(v.keys().size() == 3);
////        assertTrue(v.keys().contains("age"));
////        assertTrue(v.keys().contains("name"));
////        assertTrue(v.hiddenKeys().contains("age"));
////        assertTrue(v.property(Graph.Key.hide("color")).key().equals("color"));
////
////    }
////
//////    @Test
//////    @LoadGraphWith(MODERN)
//////    public void g_V_matchXa_created_b__b_0created_cX_whereXa_neq_cX_selectXa_c_nameX() throws Exception {
//////        Graph g = this.sqlG;
//////        final GraphReader reader = KryoReader.build().setWorkingDirectory(File.separator + "tmp").create();
//////        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.gio")) {
//////            reader.readGraph(stream, g);
//////        }
//////        Traversal<Vertex, Map<String, String>> traversal = a();
//////        printTraversalForm(traversal);
//////        List<Map<String, String>> vertices = traversal.toList();
//////        for (Map<String, String> stringStringMap : vertices) {
//////            System.out.println(stringStringMap);
//////        }
//////        assertResults(Function.identity(), traversal,
//////                new Bindings<String>().put("a", "marko").put("c", "josh"),
//////                new Bindings<String>().put("a", "marko").put("c", "peter"),
//////                new Bindings<String>().put("a", "josh").put("c", "marko"),
//////                new Bindings<String>().put("a", "josh").put("c", "peter"),
//////                new Bindings<String>().put("a", "peter").put("c", "marko"),
//////                new Bindings<String>().put("a", "peter").put("c", "josh"),
//////                new Bindings<String>().put("a", "josh").put("c", "marko")); // TODO: THIS IS REPEATED
//////    }
////
////    public Traversal<Vertex, Map<String, String>> a() {
////        return this.sqlgGraph.traversal().V().match("a",
////                this.sqlgGraph.of().as("a").out("created").as("b"),
////                this.sqlgGraph.of().as("b").in("created").as("c"))
////                .where("a", Compare.neq, "c")
////                .select(Arrays.asList("a", "c"), v -> ((Vertex) v).value("name"));
////    }
////
////    public Traversal<Vertex, Map<String, String>> b() {
////        return this.sqlgGraph.traversal().V().match("a",
////                this.sqlgGraph.of().as("a").out("created").as("b"))
//////                .where("a", T.neq, "c")
////                .select(Arrays.asList("a", "b"), v -> ((Vertex) v).value("name"));
////    }
////
////    private <S, E> void assertResults(final Function<E, String> toStringFunction,
////                                      final Traversal<S, Map<String, E>> actual,
////                                      final Bindings<E>... expected) {
////        Comparator<Bindings<E>> comp = new Bindings.BindingsComparator<>(toStringFunction);
////
////        List<Bindings<E>> actualList = toBindings(actual);
////        List<Bindings<E>> expectedList = new LinkedList<>();
////        Collections.addAll(expectedList, expected);
////
////        if (expectedList.size() > actualList.size()) {
////            fail("" + (expectedList.size() - actualList.size()) + " expected results not found, including " + expectedList.get(actualList.size()));
////        } else if (actualList.size() > expectedList.size()) {
////            fail("" + (actualList.size() - expectedList.size()) + " unexpected results, including " + actualList.get(expectedList.size()));
////        }
////
////        Collections.sort(actualList, comp);
////        Collections.sort(expectedList, comp);
////
////        for (int j = 0; j < actualList.size(); j++) {
////            Bindings<E> a = actualList.get(j);
////            Bindings<E> e = expectedList.get(j);
////
////            if (0 != comp.compare(a, e)) {
////                fail("unexpected result(s), including " + a);
////            }
////        }
////        assertFalse(actual.hasNext());
////    }
////
////    private <S, E> List<Bindings<E>> toBindings(final Traversal<S, Map<String, E>> traversal) {
////        List<Bindings<E>> result = new LinkedList<>();
////        traversal.forEachRemaining(o -> {
////            result.add(new Bindings<>(o));
////        });
////        return result;
////    }
////
////    //    @Test
////    @LoadGraphWith(MODERN)
////    public void g_v4_out_asXhereX_hasXlang_javaX_backXhereX() throws IOException {
////        Graph g = this.sqlgGraph;
////        final GraphReader reader = KryoReader.build().workingDirectory(File.separator + "tmp").create();
////        try (final InputStream stream = AbstractGremlinTest.class.getResourceAsStream("/tinkerpop-modern.gio")) {
////            reader.readGraph(stream, g);
////        }
////
////        final Traversal<Vertex, Vertex> traversal = get_g_v4_out_asXhereX_hasXlang_javaX_backXhereX(convertToVertexId("josh"));
////        printTraversalForm(traversal);
////        int counter = 0;
////        while (traversal.hasNext()) {
////            counter++;
////            final Vertex vertex = traversal.next();
////            assertEquals("java", vertex.<String>value("lang"));
////            assertTrue(vertex.value("name").equals("ripple") || vertex.value("name").equals("lop"));
////        }
////        assertEquals(2, counter);
////    }
////
////    public Traversal<Vertex, Vertex> get_g_v4_out_asXhereX_hasXlang_javaX_backXhereX(final Object v4Id) {
////        return this.sqlgGraph.v(v4Id).out().as("here").has("lang", "java").back("here");
////    }
////
////    /**
////     * Looks up the identifier as generated by the current source graph being tested.
////     *
////     * @param vertexName a unique string that will identify a graph element within a graph
////     * @return the id as generated by the graph
////     */
////    public Object convertToVertexId(final String vertexName) {
////        return convertToVertexId(this.sqlgGraph, vertexName);
////    }
////
////    /**
////     * Looks up the identifier as generated by the current source graph being tested.
////     *
////     * @param g          the graph to get the element id from
////     * @param vertexName a unique string that will identify a graph element within a graph
////     * @return the id as generated by the graph
////     */
////    public Object convertToVertexId(final Graph g, final String vertexName) {
////        return convertToVertex(g, vertexName).id();
////    }
////
////    public Vertex convertToVertex(final Graph g, final String vertexName) {
////        // all test graphs have "name" as a unique id which makes it easy to hardcode this...works for now
////        return ((Vertex) g.V().has("name", vertexName).next());
////    }
////
////
//    public void printTraversalForm(final Traversal traversal) {
//        final boolean muted = Boolean.parseBoolean(System.getProperty("muteTestLogs", "false"));
//
//        if (!muted) System.out.println("Testing: " + traversal);
////        traversal.asAdmin().applyStrategies(TraversalEngine.STANDARD); // TODO!!!!
//        if (!muted) System.out.println("         " + traversal);
//    }
////
////    private static void readGraphMLIntoGraph(final Graph g) throws IOException {
////        final GraphReader reader = GraphMLReader.build().create();
////        try (final InputStream stream = new FileInputStream(new File("sqlg-test/src/main/resources/tinkerpop-classic.xml"))) {
////            reader.readGraph(stream, g);
////        }
//////        try (final InputStream stream = TinkerpopTest.class.getResourceAsStream("tinkerpop-classic.xml")) {
//////            reader.readGraph(stream, g);
//////        }
////    }

}

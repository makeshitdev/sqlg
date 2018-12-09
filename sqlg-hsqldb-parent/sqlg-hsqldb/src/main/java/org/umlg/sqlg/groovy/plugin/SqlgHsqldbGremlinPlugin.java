package org.umlg.sqlg.groovy.plugin;

import org.apache.tinkerpop.gremlin.jsr223.AbstractGremlinPlugin;
import org.apache.tinkerpop.gremlin.jsr223.DefaultImportCustomizer;
import org.apache.tinkerpop.gremlin.jsr223.ImportCustomizer;
import org.umlg.sqlg.structure.*;
import org.umlg.sqlg.structure.topology.*;

/**
 * Date: 2014/10/11
 * Time: 9:55 AM
 */
public class SqlgHsqldbGremlinPlugin extends AbstractGremlinPlugin {

    private static final String NAME = "sqlg.hsqldb";
    private static final ImportCustomizer imports;

    static {
        try {
            imports = DefaultImportCustomizer.build()
                    .addClassImports(
                            PropertyType.class,
                            RecordId.class,
                            SchemaTable.class,
                            SqlgEdge.class,
                            SqlgElement.class,
                            SqlgGraph.class,
                            SqlgProperty.class,
                            SqlgVertex.class,
                            SqlgVertexProperty.class,
                            Topology.class,
                            EdgeLabel.class,
                            VertexLabel.class,
                            Schema.class,
                            PropertyColumn.class,
                            Index.class,
                            IndexType.class,
                            Graph.class,
                            GlobalUniqueIndex.class
                    )
                    .create();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static final SqlgHsqldbGremlinPlugin instance = new SqlgHsqldbGremlinPlugin();

    public SqlgHsqldbGremlinPlugin() {
        super(NAME, imports);
    }

    public static SqlgHsqldbGremlinPlugin instance() {
        return instance;
    }

    @Override
    public boolean requireRestart() {
        return true;
    }

}

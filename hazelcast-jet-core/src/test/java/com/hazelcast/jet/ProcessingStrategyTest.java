package com.hazelcast.jet;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IList;
import com.hazelcast.jet.dag.DAG;
import com.hazelcast.jet.dag.Edge;
import com.hazelcast.jet.dag.Vertex;
import com.hazelcast.jet.dag.sink.ListSink;
import com.hazelcast.jet.dag.source.ListSource;
import com.hazelcast.jet.job.Job;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@Category(QuickTest.class)
@RunWith(HazelcastParallelClassRunner.class)
public class ProcessingStrategyTest extends JetTestSupport {

    private static final int NODE_COUNT = 3;
    private static final int COUNT = 10_000;
    private static HazelcastInstance instance;

    @BeforeClass
    public static void initCluster() throws Exception {
        instance = createCluster(NODE_COUNT);
    }

    @Test
    public void testRoundRobin() throws Exception {
        int count = getCountWithStrategy(false, false);
        assertEquals(COUNT, count);
    }

    @Test
    public void testShuffledRoundRobin() throws Exception {
        int count = getCountWithStrategy(false, true);
        assertEquals(COUNT, count);
    }

    @Test
    public void testBroadcast() throws Exception {
        int count = getCountWithStrategy(true, false);
        assertEquals(COUNT * PARALLELISM, count);
    }

    //https://github.com/hazelcast/hazelcast-jet/issues/126
    @Test
    @Ignore
    public void testShuffledBroadcast() throws Exception {
        int count = getCountWithStrategy(true, true);
        assertEquals(COUNT * PARALLELISM * NODE_COUNT, count);
    }

    private int getCountWithStrategy(boolean broadcast, boolean shuffled) throws Exception {
        IList<Integer> source = getList(instance);
        IList<Integer> sink = getList(instance);
        fillListWithInts(source, COUNT);

        DAG dag = new DAG();

        Vertex producer = createVertex("producer", TestProcessors.Noop.class);
        Vertex consumer = createVertex("consumer", TestProcessors.Noop.class);

        dag.addVertex(producer);
        dag.addVertex(consumer);
        producer.addSource(new ListSource(source));
        consumer.addSink(new ListSink(sink));

        Edge edge = new Edge("edge", producer, consumer);
        if (broadcast) {
            edge = edge.broadcast();
        }
        if (shuffled) {
            edge = edge.shuffled();
        }
        dag.addEdge(edge);

        Job job = JetEngine.getJob(instance, randomJobName(), dag);
        execute(job);

        return sink.size();
    }
}

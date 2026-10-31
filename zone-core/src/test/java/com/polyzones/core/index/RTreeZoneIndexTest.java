package com.polyzones.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.synthetic.SyntheticPoints;
import com.polyzones.core.index.synthetic.SyntheticZones;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RTreeZoneIndexTest {
    private static final GeometryTolerance TOL=GeometryTolerance.DEFAULT;
    private static final BoundingBox METRO=new BoundingBox(77.4,12.8,77.8,13.2);
    @Test void handlesEmptyAndSingleIndexAndCandidateRanges() {
        assertThat(new RTreeZoneIndex(new Zone[0],TOL).query(0,0)).isEmpty();
        Zone zone=zone("single",square(0,0,1,1),0);
        RTreeZoneIndex one=new RTreeZoneIndex(new Zone[]{zone},TOL);
        assertThat(one.query(.5,.5)).hasSize(1);
        List<Integer> candidates=new ArrayList<>(); one.forEachCandidate(new BoundingBox(.5,.5,.6,.6),candidates::add);
        assertThat(candidates).containsExactly(0);
        candidates.clear(); one.forEachCandidate(new BoundingBox(4,4,5,5),candidates::add); assertThat(candidates).isEmpty();
    }
    @Test void overlapsUsePriorityThenCanonicalUuidOrderAndIncludeSharedBoundary() {
        Zone a=zone("00000000-0000-0000-0000-000000000001",square(0,0,1,1),4);
        Zone b=zone("00000000-0000-0000-0000-000000000002",square(0,0,1,1),4);
        Zone c=zone("00000000-0000-0000-0000-000000000003",square(0,0,1,1),9);
        List<ZoneHit> hits=new RTreeZoneIndex(new Zone[]{a,b,c},TOL).query(1,.5);
        assertThat(hits).extracting(ZoneHit::id).containsExactly(c.id(),a.id(),b.id());
        assertThat(hits).allSatisfy(h->assertThat(h.location().name()).isEqualTo("BOUNDARY"));
    }
    @Test void capacitiesAndCompleteResultsMatchIndependentNaiveScan() {
        List<Zone> zones=SyntheticZones.generate(131L,80,20,.35,.2,METRO);
        NaiveZoneIndex naive=new NaiveZoneIndex(zones.toArray(Zone[]::new),TOL);
        double[] points=SyntheticPoints.generate(7919L,20_000,METRO,.1,zones);
        for(int capacity:new int[]{4,16,64}) {
            ZoneIndex index=new RTreeZoneIndex(zones.toArray(Zone[]::new),TOL,capacity);
            for(int i=0;i<points.length;i+=2) assertThat(index.query(points[i],points[i+1])).as("capacity %s query %s",capacity,i/2).isEqualTo(naive.query(points[i],points[i+1]));
        }
    }
    @Test void candidateRetrievalEqualsIndependentExpandedBoundingBoxScan() {
        List<Zone> zones=SyntheticZones.generate(6601L,120,18,.45,.2,METRO); Zone[] array=zones.toArray(Zone[]::new);
        ZoneIndex linear=new NaiveZoneIndex(array,TOL),tree=new RTreeZoneIndex(array,TOL,4); Random random=new Random(771L);
        for(int i=0;i<2_000;i++) {
            double x=METRO.minLon()+random.nextDouble()*(METRO.maxLon()-METRO.minLon());
            double y=METRO.minLat()+random.nextDouble()*(METRO.maxLat()-METRO.minLat());
            double width=random.nextDouble()*.03,height=random.nextDouble()*.03;
            BoundingBox query=new BoundingBox(x,y,x+width,y+height);
            java.util.Set<Integer> expected=new java.util.HashSet<>(),actual=new java.util.HashSet<>();
            linear.forEachCandidate(query,expected::add); tree.forEachCandidate(query,actual::add);
            assertThat(actual).as("candidate query %s",i).isEqualTo(expected);
        }
    }
    @Test @Timeout(180) void fiftyFixedSeedScenariosMatchNaiveForOneHundredThousandPointsEach() {
        for(long seed=0x7300;seed<0x7332;seed++) {
            Random dimensions=new Random(seed); int count=1+dimensions.nextInt(24),vertices=8+dimensions.nextInt(25);
            List<Zone> zones=SyntheticZones.generate(seed,count,vertices,(seed%3)*.35,.25,METRO);
            Zone[] array=zones.toArray(Zone[]::new); ZoneIndex naive=new NaiveZoneIndex(array,TOL),tree=new RTreeZoneIndex(array,TOL,16);
            double[] points=SyntheticPoints.generate(seed^0x5a5aL,100_000,METRO,.1,zones);
            for(int i=0;i<points.length;i+=2) assertThat(tree.query(points[i],points[i+1])).as("seed %s point %s",seed,i/2).isEqualTo(naive.query(points[i],points[i+1]));
        }
    }
    @Test void nestedConcaveHoleNegativeAndIdenticalBboxesMatchNaive() {
        Zone concave=zone("concave",List.of(new LonLat(-.02,-.02),new LonLat(.02,-.02),new LonLat(.02,.02),new LonLat(.01,.02),new LonLat(.01,-.01),new LonLat(-.01,-.01),new LonLat(-.01,.02),new LonLat(-.02,.02),new LonLat(-.02,-.02)),0);
        Zone withHole=zone("hole",square(-.03,-.03,.03,.03),0,List.of(square(-.01,-.01,.01,.01)));
        Zone sameBox=zone("same",square(-.03,-.03,.03,.03),1);
        Zone[] zones={concave,withHole,sameBox}; ZoneIndex naive=new NaiveZoneIndex(zones,TOL),tree=new RTreeZoneIndex(zones,TOL);
        for(double[] point:new double[][]{{0,0},{.01,0},{-.03,0},{.05,.05},{-.02,0},{0,.03}}) assertThat(tree.query(point[0],point[1])).isEqualTo(naive.query(point[0],point[1]));
        assertThat(tree.query(.02,.02)).extracting(ZoneHit::id).contains(withHole.id(),sameBox.id());
    }
    @Test void callbackCanReenterWithoutCorruptingOuterTraversal() {
        List<Zone> zones=SyntheticZones.generate(552L,40,16,.4,0,METRO); RTreeZoneIndex tree=new RTreeZoneIndex(zones.toArray(Zone[]::new),TOL);
        List<Integer> outer=new ArrayList<>(); tree.forEachContaining(77.6,13,ordinal->{ outer.add(ordinal); tree.forEachContaining(77.6,13,ignored->{}); });
        List<Integer> expected=tree.query(77.6,13).stream().map(hit->zones.stream().filter(z->z.id().equals(hit.id())).findFirst().orElseThrow()).map(zones::indexOf).toList();
        assertThat(outer).containsExactlyInAnyOrderElementsOf(expected);
    }
    @Test void syntheticZonesAndPointsAreRepeatableForTheSameSeeds() {
        List<Zone> first=SyntheticZones.generate(818L,32,24,.5,.3,METRO),second=SyntheticZones.generate(818L,32,24,.5,.3,METRO);
        assertThat(first).hasSize(second.size());
        for(int i=0;i<first.size();i++) {
            assertThat(first.get(i).id()).isEqualTo(second.get(i).id());
            assertThat(first.get(i).shape().bounds()).isEqualTo(second.get(i).shape().bounds());
            assertThat(first.get(i).shape().vertexCount()).isEqualTo(second.get(i).shape().vertexCount());
        }
        assertThat(SyntheticPoints.generate(991L,100,METRO,.1,first)).containsExactly(SyntheticPoints.generate(991L,100,METRO,.1,second));
    }
    @Test void oneThreadLocalBufferIsReusedAcrossIndexesAndGrowsOnlyForDeeperTrees() throws Exception {
        RTreeZoneIndex small=new RTreeZoneIndex(new Zone[]{zone("small",square(0,0,.001,.001),0)},TOL);
        small.forEachContaining(.0005,.0005,ignored->{});
        java.lang.reflect.Field field=RTreeZoneIndex.class.getDeclaredField("THREAD_QUERY_BUFFER"); field.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Object> local=(ThreadLocal<Object>)field.get(null);
        Object first=local.get(); java.lang.reflect.Field stackField=first.getClass().getDeclaredField("stack"); stackField.setAccessible(true);
        int smallCapacity=((int[])stackField.get(first)).length;
        List<Zone> many=SyntheticZones.generate(903L,1_000,8,.2,0,METRO);
        RTreeZoneIndex large=new RTreeZoneIndex(many.toArray(Zone[]::new),TOL);
        large.forEachContaining(77.6,13,ignored->{});
        Object second=local.get();
        assertThat(second).isSameAs(first);
        assertThat(((int[])stackField.get(second)).length).isGreaterThanOrEqualTo(smallCapacity);
    }
    @Test @Timeout(60) void sixteenConcurrentReadersMatchSingleThreadOracle() throws Exception {
        List<Zone> zones=SyntheticZones.generate(92L,100,18,.25,.15,METRO); Zone[] array=zones.toArray(Zone[]::new);
        ZoneIndex shared=new RTreeZoneIndex(array,TOL),oracle=new NaiveZoneIndex(array,TOL);
        try(var pool=Executors.newFixedThreadPool(16)) {
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int t=0;t<16;t++) { final long seed=t; tasks.add(()->{ Random random=new Random(seed); for(int i=0;i<2_000;i++) { double x=77.4+random.nextDouble()*.4,y=12.8+random.nextDouble()*.4; assertThat(shared.query(x,y)).isEqualTo(oracle.query(x,y)); } return null; }); }
            for(var future:pool.invokeAll(tasks)) future.get();
        }
    }
    private static Zone zone(String id,List<LonLat> shell,int priority) { return zone(id,shell,priority,List.of()); }
    private static Zone zone(String id,List<LonLat> shell,int priority,List<List<LonLat>> holes) { UUID uuid; try{uuid=UUID.fromString(id);}catch(IllegalArgumentException e){uuid=UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));} return new Zone(new ZoneKey(uuid),1,ZoneType.TARIFF,priority,PolygonShape.of(shell,holes,TOL)); }
    private static List<LonLat> square(double x1,double y1,double x2,double y2) { return List.of(new LonLat(x1,y1),new LonLat(x2,y1),new LonLat(x2,y2),new LonLat(x1,y2),new LonLat(x1,y1)); }
}

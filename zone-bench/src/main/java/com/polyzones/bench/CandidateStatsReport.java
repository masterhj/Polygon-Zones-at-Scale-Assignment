package com.polyzones.bench;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.index.NaiveZoneIndex;
import com.polyzones.core.index.RTreeZoneIndex;
import com.polyzones.core.index.ZoneIndex;
import com.polyzones.core.index.synthetic.SyntheticPoints;
import com.polyzones.core.index.synthetic.SyntheticZones;
import com.polyzones.core.zone.Zone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Candidate fanout and exact-shape-check counts for the exact JMH data matrix. */
public final class CandidateStatsReport {
    private static final BoundingBox METRO=new BoundingBox(77.4,12.8,77.8,13.2);
    private static final int SAMPLE_COUNT=100_000;
    private CandidateStatsReport() { }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args.length==0?"docs/benchmarks/candidate-counts.csv":args[0]);
        List<String> lines=new ArrayList<>(); lines.add("index,polygons,vertices_per_polygon,zone_seed,point_seed,queries,mean_bbox_candidates,p95_bbox_candidates,p99_bbox_candidates,max_bbox_candidates,exact_shape_checks_per_query,mean_exact_matches");
        for(int count:new int[]{100,1_000,5_000}) for(int vertices:new int[]{16,500}) {
            long zoneSeed=0x6d697373L,pointSeed=zoneSeed^0x55aaL;
            List<Zone> zones=SyntheticZones.generate(zoneSeed,count,vertices,.12,.1,METRO);
            double[] points=SyntheticPoints.generate(pointSeed,SAMPLE_COUNT,METRO,.1,zones); Zone[] array=zones.toArray(Zone[]::new);
            ZoneIndex rtree=new RTreeZoneIndex(array,GeometryTolerance.DEFAULT),naive=new NaiveZoneIndex(array,GeometryTolerance.DEFAULT);
            int[] rtreeCounts=counts(rtree,points),naiveCounts=counts(naive,points);
            double meanMatches=meanMatches(rtree,points);
            lines.add(measure("rtree",rtreeCounts,count,vertices,zoneSeed,pointSeed,meanMatches));
            lines.add(measure("naive",naiveCounts,count,vertices,zoneSeed,pointSeed,meanMatches));
            System.out.println("candidate stats complete polygons="+count+" vertices="+vertices);
        }
        Files.createDirectories(output.getParent()); Files.write(output,lines);
    }
    private static int[] counts(ZoneIndex index,double[] points) {
        int n=points.length/2; int[] counts=new int[n];
        for(int i=0;i<n;i++) {
            double lon=points[2*i],lat=points[2*i+1]; final int ordinal=i;
            index.forEachCandidate(new BoundingBox(lon,lat,lon,lat),ignored->counts[ordinal]++);
        }
        return counts;
    }
    private static double meanMatches(ZoneIndex index,double[] points) {
        long matches=0; for(int i=0;i<points.length;i+=2) matches+=index.query(points[i],points[i+1]).size();
        return (double)matches/(points.length/2);
    }
    private static String measure(String name,int[] counts,int polygons,int vertices,long zoneSeed,long pointSeed,double meanMatches) {
        int n=counts.length;
        int[] sorted=counts.clone(); Arrays.sort(sorted);
        long total=0; for(int value:counts)total+=value;
        double checks=name.equals("rtree")?(double)total/n:polygons;
        return String.format(java.util.Locale.ROOT,"%s,%d,%d,%d,%d,%d,%.4f,%d,%d,%d,%.4f,%.6f",name,polygons,vertices,zoneSeed,pointSeed,n,(double)total/n,sorted[(int)Math.ceil(.95*n)-1],sorted[(int)Math.ceil(.99*n)-1],sorted[n-1],checks,meanMatches);
    }
}

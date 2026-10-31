package com.polyzones.core.index.synthetic;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Deterministic synthetic metro/country workloads shared by tests and benchmarks. */
public final class SyntheticZones {
    private SyntheticZones() { }
    public static List<Zone> generate(long seed,int count,int verticesPerPolygon,double overlapFactor,double holeProbability,BoundingBox extent) {
        if(count<0||verticesPerPolygon<3||overlapFactor<0||overlapFactor>1||holeProbability<0||holeProbability>1||!Double.isFinite(overlapFactor)||!Double.isFinite(holeProbability)) throw new IllegalArgumentException("Invalid generator parameters");
        Random random=new Random(seed); List<Zone> zones=new ArrayList<>(count);
        double span=Math.min(extent.maxLon()-extent.minLon(),extent.maxLat()-extent.minLat());
        double median=Math.min(.004,Math.max(.00015,span/(Math.sqrt(Math.max(1,count))*4)));
        double maxRadius=Math.min(.02,span/8);
        double centerLon=extent.centerX(),centerLat=extent.centerY();
        for(int id=0;id<count;id++) {
            double radius=Math.max(.00003,Math.min(maxRadius,median*Math.exp(.8*gaussian(random))));
            double margin=radius*1.05;
            double lon=extent.minLon()+margin+random.nextDouble()*Math.max(0,extent.maxLon()-extent.minLon()-2*margin);
            double lat=extent.minLat()+margin+random.nextDouble()*Math.max(0,extent.maxLat()-extent.minLat()-2*margin);
            lon=lon*(1-overlapFactor)+centerLon*overlapFactor; lat=lat*(1-overlapFactor)+centerLat*overlapFactor;
            double phase=random.nextDouble()*Math.PI*2;
            List<LonLat> outer=star(random,verticesPerPolygon,lon,lat,radius,phase,1);
            List<List<LonLat>> holes=random.nextDouble()<holeProbability?List.of(star(random,verticesPerPolygon,lon,lat,radius,phase,.2)):List.of();
            PolygonShape shape=PolygonShape.of(outer,holes,GeometryTolerance.DEFAULT);
            UUID uuid=UUID.nameUUIDFromBytes(("synthetic-zone:"+seed+":"+id).getBytes(StandardCharsets.UTF_8));
            zones.add(new Zone(new ZoneKey(uuid),1,ZoneType.TARIFF,random.nextInt(5),shape));
        }
        return List.copyOf(zones);
    }
    private static List<LonLat> star(Random random,int vertices,double cx,double cy,double radius,double phase,double scale) {
        List<LonLat> ring=new ArrayList<>(vertices+1);
        for(int i=0;i<vertices;i++) { double a=phase+2*Math.PI*i/vertices,r=radius*scale*(.4+.6*random.nextDouble()); ring.add(new LonLat(cx+r*Math.cos(a),cy+r*Math.sin(a))); }
        ring.add(ring.getFirst()); return ring;
    }
    private static double gaussian(Random random) { return Math.sqrt(-2*Math.log(Math.max(Double.MIN_NORMAL,random.nextDouble())))*Math.cos(2*Math.PI*random.nextDouble()); }
}

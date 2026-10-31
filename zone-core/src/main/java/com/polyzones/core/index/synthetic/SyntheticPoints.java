package com.polyzones.core.index.synthetic;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.LonLat;
import com.polyzones.core.shape.Ring;
import com.polyzones.core.zone.Zone;
import java.util.List;
import java.util.Random;

/** Fixed-seed query coordinates; returned array is interleaved longitude/latitude. */
public final class SyntheticPoints {
    private SyntheticPoints() { }
    public static double[] generate(long seed,int count,BoundingBox extent,double fractionNearBoundary,List<Zone> zones) {
        if(count<0||fractionNearBoundary<0||fractionNearBoundary>1||!Double.isFinite(fractionNearBoundary)) throw new IllegalArgumentException("Invalid point generator parameters");
        double[] points=new double[count*2]; Random random=new Random(seed);
        for(int i=0;i<count;i++) {
            if(!zones.isEmpty()&&random.nextDouble()<fractionNearBoundary) {
                Zone zone=zones.get(random.nextInt(zones.size())); Ring ring=zone.shape().outer(); int edge=random.nextInt(ring.edgeCount());
                LonLat a=ring.vertexAt(edge),b=ring.vertexAt((edge+1)%ring.edgeCount()); double t=random.nextDouble();
                double dx=b.lon()-a.lon(),dy=b.lat()-a.lat(),length=Math.hypot(dx,dy),offset=GeometryTolerance.DEFAULT.boundaryEpsDeg()*.5*(random.nextBoolean()?1:-1);
                points[i*2]=a.lon()+t*dx-(length==0?0:dy/length*offset);
                points[i*2+1]=a.lat()+t*dy+(length==0?0:dx/length*offset);
            } else {
                points[i*2]=extent.minLon()+random.nextDouble()*(extent.maxLon()-extent.minLon());
                points[i*2+1]=extent.minLat()+random.nextDouble()*(extent.maxLat()-extent.minLat());
            }
        }
        return points;
    }
}

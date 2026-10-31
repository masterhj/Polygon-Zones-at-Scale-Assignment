package com.polyzones.core.index;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.geo.Location;
import com.polyzones.core.zone.Zone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/** Immutable STR-packed bounding-box tree. All published tree storage is private primitive arrays. */
public final class RTreeZoneIndex implements ZoneIndex {
    public static final int DEFAULT_NODE_CAPACITY = 16;
    private static final int MIN_NODE_CAPACITY = 4;
    private static final ThreadLocal<QueryBuffer> THREAD_QUERY_BUFFER=ThreadLocal.withInitial(()->new QueryBuffer(0));
    private final Zone[] zones;
    private final GeometryTolerance tolerance;
    private final int nodeCapacity, root, height;
    private final double[] minX, minY, maxX, maxY;
    private final double[] zoneMinX,zoneMinY,zoneMaxX,zoneMaxY;
    private final int[] firstChild, childCount, childRefs;
    private final boolean[] leaf;

    public RTreeZoneIndex(Zone[] zones, GeometryTolerance tolerance) { this(zones, tolerance, DEFAULT_NODE_CAPACITY); }
    public RTreeZoneIndex(Zone[] source, GeometryTolerance tolerance, int nodeCapacity) {
        if (nodeCapacity < MIN_NODE_CAPACITY) throw new IllegalArgumentException("node capacity must be at least 4");
        this.zones = source.clone(); this.tolerance = tolerance; this.nodeCapacity = nodeCapacity;
        zoneMinX=new double[zones.length]; zoneMinY=new double[zones.length]; zoneMaxX=new double[zones.length]; zoneMaxY=new double[zones.length];
        List<BuildNode> level = new ArrayList<>();
        for (int i=0;i<zones.length;i++) {
            BoundingBox b=expanded(zones[i].shape().bounds(),tolerance);
            zoneMinX[i]=b.minLon(); zoneMinY[i]=b.minLat(); zoneMaxX[i]=b.maxLon(); zoneMaxY[i]=b.maxLat();
            level.add(BuildNode.entry(i,b));
        }
        int currentHeight = 0;
        while (level.size() > 1 || (level.size() == 1 && level.getFirst().leafEntry)) {
            level = pack(level, nodeCapacity);
            currentHeight++;
        }
        if (level.isEmpty()) {
            root = -1; height = 0; minX = minY = maxX = maxY = new double[0];
            firstChild = childCount = childRefs = new int[0]; leaf = new boolean[0];
        } else {
            root = 0; height = currentHeight;
            List<BuildNode> nodes = breadthFirst(level.getFirst());
            java.util.IdentityHashMap<BuildNode,Integer> ordinals = new java.util.IdentityHashMap<>();
            for (int i=0;i<nodes.size();i++) ordinals.put(nodes.get(i),i);
            minX=new double[nodes.size()]; minY=new double[nodes.size()]; maxX=new double[nodes.size()]; maxY=new double[nodes.size()];
            firstChild=new int[nodes.size()]; childCount=new int[nodes.size()]; leaf=new boolean[nodes.size()];
            int refs=nodes.stream().mapToInt(n -> n.children.length).sum(); childRefs=new int[refs];
            int cursor=0;
            for(int i=0;i<nodes.size();i++) {
                BuildNode node=nodes.get(i); minX[i]=node.box.minLon(); minY[i]=node.box.minLat(); maxX[i]=node.box.maxLon(); maxY[i]=node.box.maxLat();
                firstChild[i]=cursor; childCount[i]=node.children.length; leaf[i]=node.leafNode;
                for(BuildNode child:node.children) childRefs[cursor++]=node.leafNode?child.entryOrdinal:ordinals.get(child);
            }
        }
    }

    @Override public void forEachContaining(double lon,double lat,IntConsumer consumer) {
        if(root<0) return;
        QueryBuffer buffer=acquireBuffer();
        try {
            int top=0; buffer.stack[top++]=root;
            while(top>0) {
                int node=buffer.stack[--top];
                if(!contains(node,lon,lat)) continue;
                int start=firstChild[node], end=start+childCount[node];
                if(leaf[node]) {
                    for(int p=start;p<end;p++) { int ordinal=childRefs[p]; if(zones[ordinal].shape().locate(lon,lat,tolerance)!=Location.OUTSIDE) consumer.accept(ordinal); }
                } else {
                    for(int p=start;p<end;p++) buffer.stack[top++]=childRefs[p];
                }
            }
        } finally { buffer.inUse=false; }
    }
    @Override public List<ZoneHit> query(double lon,double lat) {
        var hits=IndexResults.newHits();
        visit(lon,lat,(ordinal,location)->hits.add(IndexResults.hit(zones[ordinal],location)));
        return IndexResults.sorted(hits);
    }
    @Override public void forEachCandidate(BoundingBox box,IntConsumer consumer) {
        if(root<0) return;
        QueryBuffer buffer=acquireBuffer();
        try {
            int top=0; buffer.stack[top++]=root;
            while(top>0) {
                int node=buffer.stack[--top]; if(!intersects(node,box)) continue;
                int start=firstChild[node],end=start+childCount[node];
                if(leaf[node]) for(int p=start;p<end;p++) { int ordinal=childRefs[p]; if(intersectsZone(ordinal,box)) consumer.accept(ordinal); }
                else for(int p=start;p<end;p++) buffer.stack[top++]=childRefs[p];
            }
        } finally { buffer.inUse=false; }
    }
    private void visit(double lon,double lat,HitConsumer consumer) {
        if(root<0) return;
        QueryBuffer buffer=acquireBuffer();
        try {
            int top=0; buffer.stack[top++]=root;
            while(top>0) {
                int node=buffer.stack[--top]; if(!contains(node,lon,lat)) continue;
                int start=firstChild[node],end=start+childCount[node];
                if(leaf[node]) for(int p=start;p<end;p++) { int ordinal=childRefs[p]; Location location=zones[ordinal].shape().locate(lon,lat,tolerance); if(location!=Location.OUTSIDE) consumer.accept(ordinal,location); }
                else for(int p=start;p<end;p++) buffer.stack[top++]=childRefs[p];
            }
        } finally { buffer.inUse=false; }
    }
    private boolean contains(int node,double x,double y) { return x>=minX[node]&&x<=maxX[node]&&y>=minY[node]&&y<=maxY[node]; }
    private boolean intersects(int node,BoundingBox b) { return minX[node]<=b.maxLon()&&maxX[node]>=b.minLon()&&minY[node]<=b.maxLat()&&maxY[node]>=b.minLat(); }
    private boolean intersectsZone(int ordinal,BoundingBox b) { return zoneMinX[ordinal]<=b.maxLon()&&zoneMaxX[ordinal]>=b.minLon()&&zoneMinY[ordinal]<=b.maxLat()&&zoneMaxY[ordinal]>=b.minLat(); }
    private int stackCapacity() { return Math.max(nodeCapacity+1,height*(nodeCapacity-1)+1); }
    private QueryBuffer acquireBuffer() {
        QueryBuffer buffer=THREAD_QUERY_BUFFER.get();
        if(buffer.inUse) { QueryBuffer nested=new QueryBuffer(stackCapacity()); nested.inUse=true; return nested; }
        if(buffer.stack.length<stackCapacity()) buffer.stack=new int[stackCapacity()];
        buffer.inUse=true; return buffer;
    }
    private static BoundingBox expanded(BoundingBox b,GeometryTolerance t) { return b.expand(t.boundaryEpsDeg()); }
    @Override public int size() { return zones.length; }
    /** Bytes occupied by the immutable primitive arrays; excludes referenced zones and object headers. */
    public long primitiveArrayBytes() {
        return 8L*(minX.length+minY.length+maxX.length+maxY.length+zoneMinX.length+zoneMinY.length+zoneMaxX.length+zoneMaxY.length)+4L*(firstChild.length+childCount.length+childRefs.length)+leaf.length;
    }

    private List<BuildNode> pack(List<BuildNode> items,int capacity) {
        List<BuildNode> sorted=new ArrayList<>(items); sorted.sort(Comparator.comparingDouble(BuildNode::centerX));
        int groups=(sorted.size()+capacity-1)/capacity, slabs=(int)Math.ceil(Math.sqrt((double)sorted.size()/capacity));
        int slabSize=(sorted.size()+slabs-1)/slabs; List<BuildNode> result=new ArrayList<>(groups);
        for(int slab=0;slab<sorted.size();slab+=slabSize) {
            List<BuildNode> slice=new ArrayList<>(sorted.subList(slab,Math.min(sorted.size(),slab+slabSize)));
            slice.sort(Comparator.comparingDouble(BuildNode::centerY));
            for(int first=0;first<slice.size();first+=capacity) result.add(BuildNode.parent(slice.subList(first,Math.min(slice.size(),first+capacity))));
        }
        return result;
    }
    private static List<BuildNode> breadthFirst(BuildNode root) {
        List<BuildNode> result=new ArrayList<>(); result.add(root);
        for(int i=0;i<result.size();i++) if(!result.get(i).leafNode) result.addAll(Arrays.asList(result.get(i).children));
        return result;
    }
    private interface HitConsumer { void accept(int ordinal,Location location); }
    private static final class QueryBuffer { int[] stack; boolean inUse; QueryBuffer(int size) { stack=new int[size]; } }
    private static final class BuildNode {
        final BoundingBox box; final BuildNode[] children; final int entryOrdinal; final boolean leafEntry,leafNode;
        private BuildNode(BoundingBox box,BuildNode[] children,int entryOrdinal,boolean leafEntry,boolean leafNode) { this.box=box;this.children=children;this.entryOrdinal=entryOrdinal;this.leafEntry=leafEntry;this.leafNode=leafNode; }
        static BuildNode entry(int ordinal,BoundingBox b) { return new BuildNode(b,new BuildNode[0],ordinal,true,false); }
        static BuildNode parent(List<BuildNode> children) { BoundingBox b=children.getFirst().box; for(int i=1;i<children.size();i++) b=b.union(children.get(i).box); return new BuildNode(b,children.toArray(BuildNode[]::new),-1,false,children.getFirst().leafEntry); }
        double centerX() { return box.centerX(); } double centerY() { return box.centerY(); }
    }
}

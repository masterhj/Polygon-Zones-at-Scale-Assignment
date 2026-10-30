package com.polyzones.core.geo;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Executable D-04 error analysis. CSV figures are generated from Vincenty inverse calculations. */
class DistanceErrorAnalysisTest {
    private static final double WGS84_A = 6_378_137.0;
    private static final double WGS84_F = 1 / 298.257223563;
    private static final double WGS84_B = (1 - WGS84_F) * WGS84_A;
    @Test void writesMeasuredHaversineAndDegreeLerpErrors() throws IOException {
        List<String> rows = new ArrayList<>(); rows.add("latitude_deg,bearing_deg,target_m,haversine_m,vincenty_m,haversine_relative_error,degree_lerp_midpoint_error_m");
        double maxRelative = 0, maxMidpoint = 0;
        for (double lat : new double[]{0, 12.97, 28.6, 60}) for (double bearing : new double[]{0,45,90}) for (double distance : new double[]{1_000,10_000,100_000}) {
            LonLat start = new LonLat(10, lat);
            LonLat end = direct(start, bearing, distance);
            double reference = vincenty(start, end);
            double haversine = Haversine.distanceMeters(start, end);
            double relative = Math.abs(haversine - reference) / reference;
            LonLat linearMid = Haversine.lerp(start, end, .5);
            LonLat geoMid = direct(start, bearing, reference / 2);
            double midpointError = Haversine.distanceMeters(linearMid, geoMid);
            maxRelative = Math.max(maxRelative, relative); maxMidpoint = Math.max(maxMidpoint, midpointError);
            rows.add(String.format(java.util.Locale.ROOT, "%.2f,%.0f,%.0f,%.6f,%.6f,%.9f,%.6f", lat, bearing, distance, haversine, reference, relative, midpointError));
        }
        Path output = Path.of("..", "docs", "benchmarks", "distance-error.csv");
        Files.createDirectories(output.getParent()); Files.write(output, rows);
        Files.writeString(Path.of("..", "docs", "benchmarks", "distance-error-summary.txt"),
                String.format(java.util.Locale.ROOT, "cases=%d%nmax_haversine_relative_error=%.9f (%.5f%%)%nmax_degree_lerp_midpoint_error_m=%.6f%n", rows.size()-1, maxRelative, 100*maxRelative, maxMidpoint));
        assertThat(rows).hasSize(37);
    }

    private static LonLat direct(LonLat from, double bearingDegrees, double distance) {
        double alpha1 = Math.toRadians(bearingDegrees), phi1 = Math.toRadians(from.lat());
        double tanU1 = (1-WGS84_F)*Math.tan(phi1), cosU1 = 1/Math.sqrt(1+tanU1*tanU1), sinU1 = tanU1*cosU1;
        double sigma1 = Math.atan2(tanU1, Math.cos(alpha1)), sinAlpha = cosU1*Math.sin(alpha1), cosSqAlpha=1-sinAlpha*sinAlpha;
        double uSq=cosSqAlpha*(WGS84_A*WGS84_A-WGS84_B*WGS84_B)/(WGS84_B*WGS84_B);
        double aa=1+uSq/16384*(4096+uSq*(-768+uSq*(320-175*uSq)));
        double bb=uSq/1024*(256+uSq*(-128+uSq*(74-47*uSq)));
        double sigma=distance/(WGS84_B*aa), previous;
        do {
            double twoSigmaM=2*sigma1+sigma, sinSigma=Math.sin(sigma), cosSigma=Math.cos(sigma), cosTwo=Math.cos(twoSigmaM);
            double delta=bb*sinSigma*(cosTwo+bb/4*(cosSigma*(-1+2*cosTwo*cosTwo)-bb/6*cosTwo*(-3+4*sinSigma*sinSigma)*(-3+4*cosTwo*cosTwo)));
            previous=sigma; sigma=distance/(WGS84_B*aa)+delta;
        } while (Math.abs(sigma-previous)>1e-13);
        double sinSigma=Math.sin(sigma), cosSigma=Math.cos(sigma), twoSigmaM=2*sigma1+sigma, cosTwo=Math.cos(twoSigmaM);
        double tmp=sinU1*sinSigma-cosU1*cosSigma*Math.cos(alpha1);
        double phi2=Math.atan2(sinU1*cosSigma+cosU1*sinSigma*Math.cos(alpha1),(1-WGS84_F)*Math.sqrt(sinAlpha*sinAlpha+tmp*tmp));
        double lambda=Math.atan2(sinSigma*Math.sin(alpha1),cosU1*cosSigma-sinU1*sinSigma*Math.cos(alpha1));
        double cc=WGS84_F/16*cosSqAlpha*(4+WGS84_F*(4-3*cosSqAlpha));
        double ll=lambda-(1-cc)*WGS84_F*sinAlpha*(sigma+cc*sinSigma*(cosTwo+cc*cosSigma*(-1+2*cosTwo*cosTwo)));
        return new LonLat(from.lon()+Math.toDegrees(ll),Math.toDegrees(phi2));
    }

    private static double vincenty(LonLat p1, LonLat p2) {
        double u1=Math.atan((1-WGS84_F)*Math.tan(Math.toRadians(p1.lat()))), u2=Math.atan((1-WGS84_F)*Math.tan(Math.toRadians(p2.lat())));
        double l=Math.toRadians(p2.lon()-p1.lon()), lambda=l, previous, sinSigma=0, cosSigma=0, sigma=0, sinAlpha=0, cosSqAlpha=0, cos2SigmaM=0;
        for(int i=0;i<200;i++) {
            double sl=Math.sin(lambda), cl=Math.cos(lambda), a=Math.cos(u2)*sl, b=Math.cos(u1)*Math.sin(u2)-Math.sin(u1)*Math.cos(u2)*cl;
            sinSigma=Math.hypot(a,b); if(sinSigma==0)return 0; cosSigma=Math.sin(u1)*Math.sin(u2)+Math.cos(u1)*Math.cos(u2)*cl;
            sigma=Math.atan2(sinSigma,cosSigma); sinAlpha=Math.cos(u1)*Math.cos(u2)*sl/sinSigma; cosSqAlpha=1-sinAlpha*sinAlpha;
            cos2SigmaM=cosSqAlpha==0?0:cosSigma-2*Math.sin(u1)*Math.sin(u2)/cosSqAlpha;
            double c=WGS84_F/16*cosSqAlpha*(4+WGS84_F*(4-3*cosSqAlpha)); previous=lambda;
            lambda=l+(1-c)*WGS84_F*sinAlpha*(sigma+c*sinSigma*(cos2SigmaM+c*cosSigma*(-1+2*cos2SigmaM*cos2SigmaM)));
            if(Math.abs(lambda-previous)<1e-12)break;
        }
        double uSq=cosSqAlpha*(WGS84_A*WGS84_A-WGS84_B*WGS84_B)/(WGS84_B*WGS84_B);
        double a=1+uSq/16384*(4096+uSq*(-768+uSq*(320-175*uSq))), b=uSq/1024*(256+uSq*(-128+uSq*(74-47*uSq)));
        double delta=b*sinSigma*(cos2SigmaM+b/4*(cosSigma*(-1+2*cos2SigmaM*cos2SigmaM)-b/6*cos2SigmaM*(-3+4*sinSigma*sinSigma)*(-3+4*cos2SigmaM*cos2SigmaM)));
        return WGS84_B*a*(sigma-delta);
    }
}

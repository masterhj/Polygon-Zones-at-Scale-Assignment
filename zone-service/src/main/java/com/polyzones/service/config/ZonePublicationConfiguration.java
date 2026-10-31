package com.polyzones.service.config;

import com.polyzones.service.persistence.GeometryCodec;
import com.polyzones.service.persistence.JdbcSnapshotLoader;
import com.polyzones.service.persistence.JdbcZoneSetStore;
import com.polyzones.service.persistence.CheckpointStateCodec;
import com.polyzones.service.persistence.JdbcTripCheckpointRepository;
import com.polyzones.service.pipeline.CheckpointRepository;
import com.polyzones.service.observability.ZoneVersionGaugeRegistry;
import com.polyzones.service.observability.PipelineMetrics;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import com.polyzones.service.publish.SnapshotRegistry;
import com.polyzones.service.publish.SnapshotLoader;
import com.polyzones.service.publish.ZoneEditCommand;
import com.polyzones.service.publish.ZoneSetPublisher;
import com.polyzones.service.application.ZoneMapper;
import com.polyzones.service.application.TripExecutionRegistry;
import com.polyzones.service.application.TripMapper;
import com.polyzones.service.pipeline.PingIngestService;
import com.polyzones.core.index.ZoneIndexFactory;
import com.polyzones.core.index.RTreeZoneIndex;
import com.polyzones.core.index.NaiveZoneIndex;
import com.polyzones.core.geo.GeometryTolerance;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ZonePublicationConfiguration {
    @Bean public Clock applicationClock() { return Clock.systemUTC(); }
    @Bean public PipelineMetrics pipelineMetrics(MeterRegistry meters) { return new PipelineMetrics(meters); }
    @Bean public GeometryCodec geometryCodec() { return new GeometryCodec(JsonMapper.builder().build()); }
    @Bean public ZoneMapper zoneMapper(GeometryCodec geometry) { return new ZoneMapper(geometry); }
    @Bean public TripMapper tripMapper() { return new TripMapper(); }
    @Bean public SnapshotRegistry snapshotRegistry() { return new SnapshotRegistry(); }
    @Bean public ZoneVersionGaugeRegistry zoneVersionGaugeRegistry(MeterRegistry meters,
                    @Value("${zones.metrics.max-tenant-tags:1000}") int tenantGaugeCap) {
        return new ZoneVersionGaugeRegistry(meters, tenantGaugeCap);
    }
    @Bean public ZoneIndexFactory zoneIndexFactory(@Value("${zones.index.strategy:rtree}") String strategy) {
        return switch (strategy.toLowerCase(java.util.Locale.ROOT)) {
            case "rtree" -> RTreeZoneIndex::new;
            case "naive" -> (zones, tolerance, capacity) -> new NaiveZoneIndex(zones, tolerance);
            default -> throw new IllegalArgumentException("zones.index.strategy must be rtree or naive");
        };
    }
    @Bean public JdbcSnapshotLoader jdbcSnapshotLoader(JdbcTemplate jdbc, GeometryCodec geometry, ZoneIndexFactory indexes, Clock clock) {
        return new JdbcSnapshotLoader(jdbc, geometry, indexes, clock);
    }
    @Bean public JdbcZoneSetStore jdbcZoneSetStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                                    GeometryCodec geometry, JdbcSnapshotLoader loader, Clock clock) {
        return new JdbcZoneSetStore(jdbc, transactionManager, geometry, loader, clock);
    }
    @Bean public CheckpointStateCodec checkpointStateCodec() { return new CheckpointStateCodec(JsonMapper.builder().build()); }
    @Bean public JdbcTripCheckpointRepository jdbcTripCheckpointRepository(JdbcTemplate jdbc,
                    PlatformTransactionManager transactionManager, CheckpointStateCodec codec,
                    PinnedSnapshotRegistry snapshots, Clock clock) {
        return new JdbcTripCheckpointRepository(jdbc, transactionManager, codec, snapshots, clock);
    }
    @Bean(destroyMethod = "close") public PingIngestService pingIngestService(TripExecutionRegistry executions,
            JdbcTemplate jdbc, Clock clock, PipelineMetrics metrics,
            @Value("${zones.pipeline.shards:0}") int configuredShards,
            @Value("${zones.pipeline.queue-capacity:10000}") int queueCapacity,
            @Value("${zones.pipeline.max-ping-age:24h}") java.time.Duration maxAge,
            @Value("${zones.pipeline.future-clock-skew:5m}") java.time.Duration futureSkew) {
        int shards = configuredShards > 0 ? configuredShards : Math.max(1, Runtime.getRuntime().availableProcessors());
        return new PingIngestService(shards, queueCapacity, clock, maxAge, futureSkew,
                executions::process,
                (tenant, trip) -> jdbc.query("select durable_acked_seq from trips where tenant_id=? and trip_id=?",
                        rs -> rs.next() ? rs.getLong(1) : -1L, tenant, trip), metrics);
    }
    @Bean public PinnedSnapshotRegistry pinnedSnapshotRegistry(SnapshotRegistry registry, SnapshotLoader loader,
                                                                @Value("${zones.cache.historical-snapshots-max-vertices:5000000}") long maxVertices) {
        return new PinnedSnapshotRegistry(registry, loader, maxVertices);
    }
    @Bean(destroyMethod = "close") public ZoneSetPublisher zoneSetPublisher(JdbcZoneSetStore store, SnapshotLoader loader,
                                                                               SnapshotRegistry registry, MeterRegistry meters,
                                                                               @Value("${zones.publisher.builder-threads:2}") int threads,
                                                                               @Value("${zones.publisher.queue-capacity:8}") int queueCapacity,
                                                                               ZoneVersionGaugeRegistry versionGauges,
                                                                               ConfigurableApplicationContext context) {
        return new ZoneSetPublisher(store, loader, registry, ZonePublicationConfiguration::validate,
                ignored -> false, meters, threads, queueCapacity, versionGauges, ignored -> context.close());
    }

    private static List<com.polyzones.core.shape.ValidationIssue> validate(ZoneEditCommand command) {
        // PolygonShape can only be created by the validating factory; web input validation is added in Phase 6.
        return List.of();
    }
}

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
    @Bean public SnapshotRegistry snapshotRegistry() { return new SnapshotRegistry(); }
    @Bean public ZoneVersionGaugeRegistry zoneVersionGaugeRegistry(MeterRegistry meters,
                    @Value("${zones.metrics.max-tenant-tags:1000}") int tenantGaugeCap) {
        return new ZoneVersionGaugeRegistry(meters, tenantGaugeCap);
    }
    @Bean public JdbcSnapshotLoader jdbcSnapshotLoader(JdbcTemplate jdbc, GeometryCodec geometry) {
        return new JdbcSnapshotLoader(jdbc, geometry);
    }
    @Bean public JdbcZoneSetStore jdbcZoneSetStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                                    GeometryCodec geometry, JdbcSnapshotLoader loader, Clock clock) {
        return new JdbcZoneSetStore(jdbc, transactionManager, geometry, loader, clock);
    }
    @Bean public CheckpointStateCodec checkpointStateCodec() { return new CheckpointStateCodec(JsonMapper.builder().build()); }
    @Bean public CheckpointRepository jdbcTripCheckpointRepository(JdbcTemplate jdbc,
                    PlatformTransactionManager transactionManager, CheckpointStateCodec codec,
                    PinnedSnapshotRegistry snapshots, Clock clock) {
        return new JdbcTripCheckpointRepository(jdbc, transactionManager, codec, snapshots, clock);
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

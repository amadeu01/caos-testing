package io.github.amadeu01.chaos.catalog;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Writes the seed titles at startup. Idempotent: PutItem overwrites, so restarts are safe. */
@ApplicationScoped
public class CatalogSeeder {

    private static final Logger LOG = Logger.getLogger(CatalogSeeder.class);

    @Inject
    TitleRepository repository;

    void onStart(@Observes StartupEvent event) {
        var titles = TitleSeed.titles();
        repository.saveAll(titles);
        LOG.infof("Seeded %d titles", titles.size());
    }
}

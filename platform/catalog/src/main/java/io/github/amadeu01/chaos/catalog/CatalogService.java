package io.github.amadeu01.chaos.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CatalogService {

    @Inject
    TitleCache cache;

    @Inject
    TitleRepository repository;

    public List<Title> all() {
        return cache.get().orElseGet(() -> {
            List<Title> titles = repository.findAll();
            cache.put(titles);
            return titles;
        });
    }

    public Optional<Title> byId(String id) {
        return repository.findById(id);
    }
}

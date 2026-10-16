# Maven multi-module build with the Maven wrapper

The repository is one Maven multi-module project built with `./mvnw`, opened in IntelliJ IDEA as a single project. Quarkus supports Gradle too, but Maven is the default in the Quarkus guides and code.quarkus.io, handles multi-module `quarkus dev` more maturely, and Gradle's configuration cache is incompatible with `quarkusDev`. The wrapper pins the Maven version so students need only a JDK.

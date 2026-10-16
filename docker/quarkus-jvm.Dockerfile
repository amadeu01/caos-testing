# Shared by every Quarkus module; the build context is the module directory.
FROM eclipse-temurin:25.0.4.1_1-jre-noble
WORKDIR /deployments
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/*.jar ./
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/
EXPOSE 8080
USER 1001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.util.logging.manager=org.jboss.logmanager.LogManager -jar quarkus-run.jar"]

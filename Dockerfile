FROM clojure:temurin-21-tools-deps-jammy AS builder

# Copying and building deps as a separate step in order to mitigate
# the need to download new dependencies every build.
RUN apt-get update && apt-get install -y npm
COPY deps.edn /usr/src/app/deps.edn
WORKDIR /usr/src/app
RUN clojure -X:deps prep
RUN clojure -P
COPY . /usr/src/app
RUN clojure -T:build uber

# Using image without lein for deployment.
FROM eclipse-temurin:21-jammy
LABEL maintainer="Tristan Nelson <thnelson@geisinger.edu>"

COPY --from=builder /usr/src/app/target/app.jar /app/app.jar

EXPOSE 8888

CMD ["java", "-jar", "/app/app.jar"]
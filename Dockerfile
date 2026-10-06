# syntax=docker/dockerfile:1
# Hyperscan only supports x86-64 (SSSE3 or newer); Ubuntu ships libhyperscan5 for amd64 only.

FROM --platform=linux/amd64 eclipse-temurin:25-jdk-noble AS build
RUN apt-get update \
	&& apt-get install -y --no-install-recommends libhyperscan5 \
	&& rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B package

FROM --platform=linux/amd64 eclipse-temurin:25-jre-noble
RUN apt-get update \
	&& apt-get install -y --no-install-recommends libhyperscan5 \
	&& rm -rf /var/lib/apt/lists/* \
	&& useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/hyperscan-api.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]

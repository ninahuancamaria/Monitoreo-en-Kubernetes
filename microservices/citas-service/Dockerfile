# ── Etapa 1: BUILD ──────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# Copiar pom.xml primero para cachear dependencias
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Compilar y empaquetar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Etapa 2: RUN ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Usuario no-root
RUN addgroup -S veterinaria && adduser -S citas -G veterinaria

# Solo el JAR final
COPY --from=build /app/target/citas-service-1.0.0.jar app.jar

USER citas

EXPOSE 8082

# Variables de entorno con valores por defecto
# En Kubernetes se sobreescriben desde el Deployment manifest
ENV PACIENTES_SERVICE_URL=http://pacientes-service:8081
ENV PACIENTES_TIMEOUT_SEGUNDOS=3

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

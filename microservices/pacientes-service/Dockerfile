# ── Etapa 1: BUILD ──────────────────────────────────────────────────────────
# Usa la imagen oficial de Maven con JDK 17 para compilar y empaquetar
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# Copiar primero el pom.xml para aprovechar el cache de capas de Docker.
# Solo se re-descarga si cambia el pom, no si cambia el código fuente.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copiar el código fuente y empaquetar (saltando tests para la imagen)
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Etapa 2: RUN ─────────────────────────────────────────────────────────────
# Imagen final liviana: solo JRE 17, sin Maven ni código fuente
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Crear usuario no-root por seguridad
RUN addgroup -S veterinaria && adduser -S pacientes -G veterinaria

# Copiar únicamente el JAR generado en la etapa anterior
COPY --from=build /app/target/pacientes-service-1.0.0.jar app.jar

# Cambiar al usuario no-root
USER pacientes

# Puerto que expone la aplicación
EXPOSE 8081

# Variable de entorno para el delay artificial (Casuística 2)
ENV ARTIFICIAL_DELAY_MS=0

# Comando de arranque con opciones JVM optimizadas para contenedores
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]

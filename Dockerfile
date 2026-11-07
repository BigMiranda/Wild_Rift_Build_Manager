# Versão pública (Rodada 11, opção 1 de docs/rodada-11.md): o jar no perfil `publico` com o banco exportado.
# Nada aqui publica: é só a receita da imagem. Antes do build, exporte o banco:
#   cd backend && ./mvnw -Pexport        (gera backend/data/publico/ornn-planner-publico.db)
# Depois, na raiz do repositório:
#   docker build -t ornn-planner-publico .
#   docker run --rm -p 8080:8080 ornn-planner-publico
# Para trocar o banco sem refazer a imagem, monte outro por cima:
#   docker run --rm -p 8080:8080 -v "$PWD/backend/data/publico:/app/data/publico:ro" ornn-planner-publico
# As builds compartilhadas pelos visitantes (Parte 4) ficam no volume /app/data/comunidade:
#   docker run -p 8080:8080 -v ornn-comunidade:/app/data/comunidade -e PLANNER_COMMUNITY_ADMIN_TOKEN=... ornn-planner-publico

# 1. A página em modo público (VITE_PUBLIC=1), escrita em backend/src/main/resources/static
FROM node:20-alpine AS front
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund
COPY frontend/ ./
RUN mkdir -p ../backend/src/main/resources/static && npm run build:publico

# 2. O jar, com a página dentro
FROM eclipse-temurin:17-jdk-alpine AS back
WORKDIR /src/backend
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn ./.mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=front /src/backend/src/main/resources/static ./src/main/resources/static
RUN ./mvnw -q -B package -DskipTests && cp target/*.jar /app.jar

# 3. Só o JRE, o jar e o banco somente leitura, sem root
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=back /app.jar /app/app.jar
COPY backend/data/publico/ornn-planner-publico.db /app/data/publico/ornn-planner-publico.db
# the builds the visitors share (Rodada 11, part 4) are the only file written: mount a volume to keep them
RUN mkdir -p /app/data/comunidade && chown -R app:app /app && chmod 0444 /app/data/publico/ornn-planner-publico.db
VOLUME /app/data/comunidade
USER app
ENV SPRING_PROFILES_ACTIVE=publico \
    PLANNER_DB_PATH=/app/data/publico/ornn-planner-publico.db \
    PLANNER_COMMUNITY_DB_PATH=/app/data/comunidade/comunidade.db \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://localhost:8080/api/public/info >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

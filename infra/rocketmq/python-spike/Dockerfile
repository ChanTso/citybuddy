FROM ghcr.io/astral-sh/uv:0.11.24@sha256:99ea34acedc870ba4ad11a1f540a1c04267c9f30aadc465a94406f52dfda2c36 AS uv

FROM python:3.11.15-slim-bookworm@sha256:b18992999dbe963a45a8a4da40ac2b1975be1a776d939d098c647482bcad5cba

COPY --from=uv /uv /uvx /bin/

WORKDIR /opt/citybuddy

COPY pyproject.toml uv.lock ./
COPY agent-service/pyproject.toml ./agent-service/pyproject.toml
COPY agent-service/src ./agent-service/src
COPY knowledge-indexer/pyproject.toml ./knowledge-indexer/pyproject.toml
COPY knowledge-indexer/src ./knowledge-indexer/src

RUN uv sync --locked --no-dev --package citybuddy-knowledge-indexer

ENTRYPOINT ["/opt/citybuddy/.venv/bin/python", "-m", "citybuddy_indexer.rocketmq_spike"]

FROM python:3.11-slim
RUN useradd --uid 10001 --create-home app
WORKDIR /app
COPY worker /app/worker
COPY vendor/distilly /app/vendor/distilly
USER 10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["python", "worker/worker.py"]

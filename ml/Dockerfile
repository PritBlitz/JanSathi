# The internal ML service: docker compose --profile ml up (see README.md).
# Weights are not in the image; they live in the volume mounted at /models.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 ML_MODELS_DIR=/models
RUN useradd --system --uid 10002 --home /app ml && mkdir -p /models && chown ml /models
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
USER ml
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD python -c "import urllib.request as u; u.urlopen('http://127.0.0.1:8090/healthz', timeout=2)"
CMD ["python", "service.py", "--host", "0.0.0.0", "--port", "8090"]

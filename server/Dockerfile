FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home mozhi && mkdir /data && chown mozhi /data
COPY mozhi_service ./mozhi_service
COPY scripts ./scripts
USER mozhi
ENV MOZHI_AGENT_DB=/data/agent.sqlite3 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8080 8081
CMD ["uvicorn", "mozhi_service.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log", "--no-proxy-headers"]

FROM python:3.11-slim

WORKDIR /app

# 시스템 패키지 (numpy/torch 빌드 의존성, healthcheck용 curl)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Python 의존성 (캐시 활용 위해 requirements.txt 먼저)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 애플리케이션 코드
COPY main.py .

# HuggingFace 캐시 디렉터리 (볼륨 마운트 대상)
ENV HF_HOME=/root/.cache/huggingface
ENV TRANSFORMERS_CACHE=/root/.cache/huggingface

EXPOSE 8000

# uvicorn 단일 워커 (모델이 메모리 점유 크므로 멀티 워커 비권장)
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]

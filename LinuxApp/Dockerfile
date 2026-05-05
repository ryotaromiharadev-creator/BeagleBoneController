FROM --platform=linux/arm/v7 arm32v7/debian:bookworm-slim

RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    gcc \
    make \
    libc6-dev \
    iproute2 \
    iputils-ping \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN python3 -m venv /venv && /venv/bin/pip install --no-cache-dir -r requirements.txt

COPY . .

ENV PATH="/venv/bin:$PATH"

CMD ["/bin/bash"]

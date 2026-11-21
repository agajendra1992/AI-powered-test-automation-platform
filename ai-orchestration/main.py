"""Entry point: python main.py (AI_HOST / AI_PORT, default 127.0.0.1:8095)."""
import logging
import os

import uvicorn

from app.api import create_app

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    uvicorn.run(create_app(), host=os.environ.get("AI_HOST", "127.0.0.1"), port=int(os.environ.get("AI_PORT", "8095")))

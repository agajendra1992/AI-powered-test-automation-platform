"""
Demo system under test ("QA Shop Admin") used to verify the automation platform.

Deliberately small but realistic: token auth, OAuth2 client credentials, Basic auth,
user CRUD with validation, payments with refund, file upload/download, correlation ids,
and a browser UI built on semantic HTML (labels, roles) plus stable data-testid hooks.

In-memory only. NOT for production use. Credentials come from environment variables;
the defaults exist only so the bundled demo runs locally.
"""

from __future__ import annotations

import base64
import os
import secrets
import time
import uuid
from datetime import datetime, timezone
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, PlainTextResponse, Response
from pydantic import BaseModel, EmailStr, Field, field_validator



def _setting(name: str, default: str) -> str:
    """Environment override; unset or blank (e.g. an empty compose/CI variable) means the demo default."""
    value = os.getenv(name, "").strip()
    return value or default


ADMIN_USER = _setting("SUT_ADMIN_USER", "qa.admin")
ADMIN_PASSWORD = _setting("SUT_ADMIN_PASSWORD", "local-demo-only")
CLIENT_ID = _setting("SUT_CLIENT_ID", "qa-client")
CLIENT_SECRET = _setting("SUT_CLIENT_SECRET", "local-demo-client-secret")
TOKEN_TTL = 3600

app = FastAPI(title="QA Shop Admin (demo SUT)", version="1.0.0")

# ----------------------------------------------------------------------------- state
USERS: dict[str, dict] = {}
CREDENTIALS: dict[str, dict] = {ADMIN_USER: {"password": ADMIN_PASSWORD, "user_id": None, "display": "QA Admin"}}
TOKENS: dict[str, dict] = {}
PAYMENTS: dict[str, dict] = {}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


@app.middleware("http")
async def correlation_id(request: Request, call_next):
    cid = request.headers.get("x-correlation-id") or str(uuid.uuid4())
    response = await call_next(request)
    response.headers["X-Correlation-Id"] = cid
    return response


# ----------------------------------------------------------------------------- auth
def issue_token(username: str) -> str:
    token = secrets.token_urlsafe(24)
    TOKENS[token] = {"username": username, "expires": time.time() + TOKEN_TTL}
    return token


def current_user(authorization: Optional[str] = Header(default=None)) -> str:
    if not authorization:
        raise HTTPException(401, "Missing Authorization header")
    scheme, _, value = authorization.partition(" ")
    if scheme.lower() == "bearer":
        entry = TOKENS.get(value)
        if not entry or entry["expires"] < time.time():
            raise HTTPException(401, "Invalid or expired token")
        return entry["username"]
    if scheme.lower() == "basic":
        try:
            user, _, pwd = base64.b64decode(value).decode().partition(":")
        except Exception:  # noqa: BLE001 - malformed header is a client error
            raise HTTPException(401, "Malformed Basic credentials")
        cred = CREDENTIALS.get(user)
        if not cred or not secrets.compare_digest(cred["password"], pwd):
            raise HTTPException(401, "Invalid credentials")
        return user
    raise HTTPException(401, "Unsupported auth scheme")


class LoginRequest(BaseModel):
    username: str
    password: str


@app.post("/api/auth/login")
def login(body: LoginRequest):
    cred = CREDENTIALS.get(body.username)
    if not cred or not secrets.compare_digest(cred["password"], body.password):
        raise HTTPException(401, "Invalid username or password")
    return {"token": issue_token(body.username), "expires_in": TOKEN_TTL}


@app.post("/api/oauth/token")
def oauth_token(grant_type: str = Form(...), client_id: str = Form(...), client_secret: str = Form(...),
                scope: Optional[str] = Form(default=None)):
    if grant_type != "client_credentials":
        raise HTTPException(400, "unsupported_grant_type")
    if client_id != CLIENT_ID or not secrets.compare_digest(client_secret, CLIENT_SECRET):
        raise HTTPException(401, "invalid_client")
    return {"access_token": issue_token(f"client:{client_id}"), "token_type": "Bearer", "expires_in": TOKEN_TTL,
            "scope": scope or ""}


@app.get("/api/me")
def me(user: str = Depends(current_user)):
    cred = CREDENTIALS.get(user, {})
    return {"username": user, "displayName": cred.get("display", user), "userId": cred.get("user_id")}


@app.get("/api/health")
def health():
    return {"status": "UP", "time": now()}


# ----------------------------------------------------------------------------- users
class UserIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    email: EmailStr
    job: str = Field(default="", max_length=80)
    password: Optional[str] = Field(default=None, min_length=8, max_length=64)


class UserPatch(BaseModel):
    name: Optional[str] = Field(default=None, min_length=1, max_length=80)
    email: Optional[EmailStr] = None
    job: Optional[str] = Field(default=None, max_length=80)


def public_user(u: dict) -> dict:
    return {k: u[k] for k in ("id", "name", "email", "job", "createdAt", "updatedAt")}


def get_or_404(uid: str) -> dict:
    if uid not in USERS:
        raise HTTPException(404, f"User {uid} not found")
    return USERS[uid]


@app.post("/api/users", status_code=201)
def create_user(body: UserIn, _: str = Depends(current_user)):
    if any(u["email"].lower() == body.email.lower() for u in USERS.values()):
        raise HTTPException(409, "Email already registered")
    uid = uuid.uuid4().hex[:12]
    user = {"id": uid, "name": body.name, "email": body.email, "job": body.job, "createdAt": now(), "updatedAt": now()}
    USERS[uid] = user
    if body.password:
        CREDENTIALS[body.email] = {"password": body.password, "user_id": uid, "display": body.name}
    return public_user(user)


@app.get("/api/users")
def list_users(page: int = 1, size: int = 20, q: str = "", _: str = Depends(current_user)):
    if page < 1 or not 1 <= size <= 100:
        raise HTTPException(400, "page must be >= 1 and size between 1 and 100")
    items = [u for u in USERS.values() if q.lower() in (u["name"] + " " + u["email"]).lower()]
    start = (page - 1) * size
    return {"page": page, "size": size, "total": len(items), "data": [public_user(u) for u in items[start:start + size]]}


@app.get("/api/users/{uid}")
def get_user(uid: str, _: str = Depends(current_user)):
    return public_user(get_or_404(uid))


@app.put("/api/users/{uid}")
def replace_user(uid: str, body: UserIn, _: str = Depends(current_user)):
    user = get_or_404(uid)
    user.update(name=body.name, email=body.email, job=body.job, updatedAt=now())
    return public_user(user)


@app.patch("/api/users/{uid}")
def patch_user(uid: str, body: UserPatch, _: str = Depends(current_user)):
    user = get_or_404(uid)
    user.update({k: v for k, v in body.model_dump(exclude_none=True).items()}, updatedAt=now())
    return public_user(user)


@app.delete("/api/users/{uid}", status_code=204)
def delete_user(uid: str, _: str = Depends(current_user)):
    user = get_or_404(uid)
    CREDENTIALS.pop(user["email"], None)
    del USERS[uid]
    return Response(status_code=204)


# ----------------------------------------------------------------------------- payments
DECLINED_CARDS = {"4000000000000002"}


def luhn_ok(number: str) -> bool:
    digits = [int(d) for d in number][::-1]
    total = sum(d if i % 2 == 0 else (d * 2 - 9 if d * 2 > 9 else d * 2) for i, d in enumerate(digits))
    return total % 10 == 0


class PaymentIn(BaseModel):
    cardholder: str = Field(min_length=2, max_length=60)
    cardNumber: str
    expiry: str
    cvv: str
    amount: float = Field(gt=0, le=10000)
    currency: str = "USD"

    @field_validator("cardNumber")
    @classmethod
    def card_valid(cls, v: str) -> str:
        v = v.replace(" ", "")
        if not v.isdigit() or not 13 <= len(v) <= 19 or not luhn_ok(v):
            raise ValueError("invalid card number")
        return v

    @field_validator("expiry")
    @classmethod
    def expiry_valid(cls, v: str) -> str:
        try:
            month, year = v.split("/")
            exp = datetime(2000 + int(year), int(month), 1, tzinfo=timezone.utc)
        except Exception:  # noqa: BLE001
            raise ValueError("expiry must be MM/YY")
        if exp < datetime.now(timezone.utc).replace(day=1, hour=0, minute=0, second=0, microsecond=0):
            raise ValueError("card has expired")
        return v

    @field_validator("cvv")
    @classmethod
    def cvv_valid(cls, v: str) -> str:
        if not (v.isdigit() and len(v) in (3, 4)):
            raise ValueError("invalid CVV")
        return v

    @field_validator("currency")
    @classmethod
    def currency_valid(cls, v: str) -> str:
        if v not in {"USD", "EUR", "GBP", "INR"}:
            raise ValueError("unsupported currency")
        return v


def public_payment(p: dict) -> dict:
    return {k: p[k] for k in ("id", "status", "amount", "currency", "last4", "reference", "refundedAmount", "createdAt")}


@app.post("/api/payments", status_code=201)
def create_payment(body: PaymentIn, _: str = Depends(current_user)):
    pid = uuid.uuid4().hex[:12]
    status = "DECLINED" if body.cardNumber in DECLINED_CARDS else "APPROVED"
    payment = {"id": pid, "status": status, "amount": round(body.amount, 2), "currency": body.currency,
               "last4": body.cardNumber[-4:], "reference": "PAY-" + pid[:8].upper(), "refundedAmount": 0.0,
               "createdAt": now()}
    PAYMENTS[pid] = payment
    if status == "DECLINED":
        return JSONResponse(status_code=402, content={"detail": "Payment declined by issuer", **public_payment(payment)})
    return public_payment(payment)


@app.get("/api/payments/{pid}")
def get_payment(pid: str, _: str = Depends(current_user)):
    if pid not in PAYMENTS:
        raise HTTPException(404, f"Payment {pid} not found")
    return public_payment(PAYMENTS[pid])


class RefundIn(BaseModel):
    amount: float = Field(gt=0)


@app.post("/api/payments/{pid}/refund")
def refund(pid: str, body: RefundIn, _: str = Depends(current_user)):
    p = PAYMENTS.get(pid)
    if p is None:
        raise HTTPException(404, f"Payment {pid} not found")
    if p["status"] not in ("APPROVED", "PARTIALLY_REFUNDED"):
        raise HTTPException(409, f"Cannot refund a {p['status']} payment")
    if round(p["refundedAmount"] + body.amount, 2) > p["amount"]:
        raise HTTPException(422, "Refund exceeds captured amount")
    p["refundedAmount"] = round(p["refundedAmount"] + body.amount, 2)
    p["status"] = "REFUNDED" if p["refundedAmount"] == p["amount"] else "PARTIALLY_REFUNDED"
    return public_payment(p)


# ----------------------------------------------------------------------------- files
@app.post("/api/files")
async def upload(file: UploadFile = File(...), _: str = Depends(current_user)):
    data = await file.read()
    return {"filename": file.filename, "size": len(data)}


@app.get("/api/reports/users.csv")
def users_report(_: str = Depends(current_user)):
    rows = ["id,name,email,job"] + [f'{u["id"]},{u["name"]},{u["email"]},{u["job"]}' for u in USERS.values()]
    return PlainTextResponse("\n".join(rows) + "\n", media_type="text/csv",
                             headers={"Content-Disposition": 'attachment; filename="users-report.csv"'})


# ----------------------------------------------------------------------------- contract-test provider states
# Pact provider verification asks the provider to set up named states before each interaction.
# Off unless PACT_PROVIDER_STATES=1, so a normal run never exposes it.
PACT_STATE_USER = {"id": "c0ffee000001", "name": "Contract User", "email": "contract.user@example.com", "job": "QA"}


class ProviderState(BaseModel):
    state: Optional[str] = None
    action: Optional[str] = None
    params: Optional[dict] = None


if os.getenv("PACT_PROVIDER_STATES") == "1":
    @app.post("/_pact/provider-states", include_in_schema=False)
    def pact_provider_state(body: ProviderState):
        uid = PACT_STATE_USER["id"]
        if body.state == "a user with id c0ffee000001 exists":
            if body.action == "teardown":
                USERS.pop(uid, None)
            else:
                USERS[uid] = dict(PACT_STATE_USER, createdAt=now(), updatedAt=now())
        elif body.state == "no user with id c0ffee000404 exists":
            USERS.pop("c0ffee000404", None)
        elif body.state == "no user with email new.user@example.com exists":
            for key in [k for k, u in USERS.items() if u["email"].lower() == "new.user@example.com"]:
                USERS.pop(key)
        return {}


# ----------------------------------------------------------------------------- UI
from ui import PAGES  # noqa: E402  (keeps HTML out of the API module)


def page(name: str, **subs) -> HTMLResponse:
    html = PAGES[name]
    for k, v in subs.items():
        html = html.replace("{{" + k + "}}", v)
    return HTMLResponse(PAGES["layout"].replace("{{content}}", html).replace("{{title}}", subs.get("title", name)))


@app.get("/", include_in_schema=False)
def root():
    return Response(status_code=302, headers={"Location": "/login"})


@app.get("/login", include_in_schema=False)
def login_page():
    return page("login", title="Sign in")


@app.get("/dashboard", include_in_schema=False)
def dashboard_page():
    return page("dashboard", title="Dashboard")


@app.get("/users", include_in_schema=False)
def users_page():
    return page("users", title="User management")


@app.get("/users/{uid}", include_in_schema=False)
def profile_page(uid: str):
    return page("profile", title="User profile", uid=uid)


@app.get("/payments/new", include_in_schema=False)
def payment_page():
    return page("payment", title="New payment")


@app.get("/files", include_in_schema=False)
def files_page():
    return page("files", title="Files")

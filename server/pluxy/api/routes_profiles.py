from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

from ..profiles import registry
from ..state import get_state

router = APIRouter(prefix="/api/profiles", tags=["profiles"])


class ProfileBody(BaseModel):
    name: str = Field(min_length=1, max_length=40)


@router.get("")
def profiles(request: Request):
    return registry(get_state(request)).list()


@router.post("", status_code=201)
def create_profile(body: ProfileBody, request: Request):
    try:
        return registry(get_state(request)).create(body.name)
    except ValueError as error:
        raise HTTPException(422, str(error)) from error

"""Actual fixed query prefix at 6000 tokens; synthetic text, no quality claim."""
import json
import time
import urllib.error
import urllib.request
import uuid
from spec import SPEC, FINGERPRINT, tokenizer
from contract import validate


def call(path, body=None):
    request = urllib.request.Request("http://127.0.0.1:8091"+path,
        data=None if body is None else json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=305) as response:
        raw = response.read(1048577)
    if len(raw)>1048576:
        raise ValueError("response limit")
    value = json.loads(raw)
    if value.get("spec")!=SPEC["id"] or value.get("fingerprint")!=FINGERPRINT:
        raise ValueError("spec mismatch")
    return raw,value


tokens=tokenizer()
prefix=SPEC["query_prefix"]
base=len(tokens.encode(prefix).ids)
text=prefix+"图"*(6001-base)  # Prefix's final space merges with the following token.
if len(tokens.encode(text).ids)!=6000 or len(text)>8000:
    raise ValueError("exact bounded query fixture mismatch")
_,counted=call("/tokenize",{"spec":SPEC["id"],"input":[text]})
if counted["counts"]!=[6000]:
    raise ValueError("exact query count mismatch")
operation=str(uuid.uuid4())
start=time.monotonic()
raw,_=call("/embeddings",{"spec":SPEC["id"],"operation_id":operation,"input":[text]})
validate(raw,model=SPEC["model"],inputs=1,dimensions=1024,tokens=6000)
_,state=call("/operations/"+operation)
if state["state"]!="SUCCEEDED":
    raise ValueError("query completion unconfirmed")
try:
    call("/tokenize",{"spec":SPEC["id"],"input":[text+"图"]})
    raise ValueError("6001 was accepted")
except urllib.error.HTTPError as error:
    if error.code!=400:
        raise
print(json.dumps({"mode":"query-prefix-6000","tokens":6000,"reject6001":True,
    "dimensions":1024,"unit_norm":True,"seconds":round(time.monotonic()-start,3),"fingerprint":FINGERPRINT}))

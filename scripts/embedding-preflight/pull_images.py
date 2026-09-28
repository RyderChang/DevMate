"""Only pull the two digest-locked disposable probe images."""
from isolation import ENV, docker

for service in ("qdrant", "mysql"):
    docker("pull", "--platform", ENV[service]["platform"], ENV[service]["image"], timeout=600)
    print(service + " locked image ready", flush=True)

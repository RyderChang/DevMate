# Local synthetic-data probe only. Build source and verify hashes with build-minio.py.
FROM scratch
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      devmate.preflight="dev016"
COPY --chmod=755 --chown=0:0 minio /minio
COPY --chmod=644 --chown=0:0 LICENSE /LICENSE
ENV MINIO_BROWSER=off HOME=/tmp
EXPOSE 9000
ENTRYPOINT ["/minio"]
CMD ["server", "/data", "--address", ":9000"]

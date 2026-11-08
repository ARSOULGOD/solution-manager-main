# Stand-in MI for the skill's own Stage 3 test (NOT the File Mesh connector). Built on the repo's stand-in image,
# which already carries Python (Alpine); PyYAML from apk is MIT-licensed (I8).
FROM sm-standin-cache:latest
RUN apk add --no-cache py3-yaml
COPY standin_mi.py /app/standin_mi.py
WORKDIR /app/work
ENTRYPOINT ["python3", "/app/standin_mi.py"]

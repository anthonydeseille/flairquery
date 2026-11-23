# Carrier image for the agent jar: used as an initContainer that copies the jar into a shared emptyDir.
# (Phase 7 replaces the manual initContainer with an admission webhook.)
FROM busybox:1.37
COPY flairquery-agent.jar /fq-dist/agent.jar
USER 65532:65532
ENTRYPOINT ["cp", "/fq-dist/agent.jar", "/fq/agent.jar"]

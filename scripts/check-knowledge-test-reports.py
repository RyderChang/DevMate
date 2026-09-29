"""Require the full backend and each storage acceptance suite, without exposing logs."""
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    root = Path(__file__).resolve().parent.parent
    reports = list((root / "devmate-server/target/surefire-reports").glob("TEST-*.xml"))
    suites = {ET.parse(path).getroot().attrib["name"]: ET.parse(path).getroot() for path in reports}
    required = {"com.devmate.knowledge." + name for name in (
        "DocumentApiIntegrationTest", "DocumentHttpIntegrationTest", "DocumentLifecycleIntegrationTest",
        "DocumentServiceTest", "DocumentValidatorTest", "KnowledgePropertiesTest",
        "S3ObjectStorageContractTest", "UploadTempFilesTest", "TextChunkerTest", "DocumentProcessingIntegrationTest", "ProcessingServiceTest",
        "DocumentIndexingIntegrationTest", "DocumentRetrievalIntegrationTest", "RetrievalConfigurationTest", "QdrantVectorStoreIntegrationTest")}
    required.add("com.devmate.ai.LocalEmbeddingGatewayTest")
    if not required.issubset(suites):
        raise RuntimeError("Required knowledge storage/processing/indexing/retrieval suites are missing")
    totals = {key: sum(int(suite.attrib[key]) for suite in suites.values())
              for key in ("tests", "failures", "errors", "skipped")}
    if any(int(suites[name].attrib["tests"]) < 1 for name in required) or any(totals[key] for key in ("failures", "errors", "skipped")):
        raise RuntimeError("All backend tests must execute and pass without skips")
    print("PASS: full backend reports", totals)


if __name__ == "__main__":
    main()

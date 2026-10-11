"""Content-free experiment records; coverage is independent from inline markers."""
import re

SCHEMA_VERSION = "rag-experiment-collection-v2"
VERDICTS = {"SUPPORTED", "CONTRADICTED", "INSUFFICIENT", "UNKNOWN"}
CITATION_IDS = {"C1", "C2", "C3", "C4", "C5"}
SOURCE_FIELDS = ("pointId", "documentId", "filename", "processingId", "indexId",
                 "processingGeneration", "indexGeneration", "parserVersion", "strategyVersion",
                 "sourceSha256", "chunkSha256", "ordinal", "start", "end", "startLine", "endLine")


def covers(source, target):
    return (source.get("sourceSha256") == target["sourceSha256"]
            and type(source.get("startLine")) is int and type(source.get("endLine")) is int
            and source["startLine"] <= target["startLine"]
            and source["endLine"] >= target["endLine"])


def collect(answer, citations, targets, verdicts=None):
    """Missing citation data is UNKNOWN; an explicitly empty list is no coverage.

    Semantic judgements must come from review of the transient answer, never a keyword regex.
    The returned value is built from a whitelist; it contains neither answer nor source text.
    """
    verdicts = verdicts or {}
    if any(value not in VERDICTS for value in verdicts.values()):
        raise ValueError("Invalid semantic verdict")
    markers = sorted(set("C" + value for value in re.findall(r"\[C([^\]\r\n]*)\]", answer or "")))
    # Enforce the frozen five-candidate contract before copying IDs into a record.
    # Never include the rejected ID or marker text in an exception.
    if any(marker not in CITATION_IDS for marker in markers):
        raise ValueError("Invalid inline citation ID")
    if citations is not None and any(c.get("citationId") not in CITATION_IDS for c in citations):
        raise ValueError("Invalid citation ID")
    safe_citations = None if citations is None else [
        {"citationId": c["citationId"], "source": {k: c.get("source", {}).get(k)
                                                   for k in SOURCE_FIELDS}}
        for c in citations]
    slots = {}
    for slot, target in targets.items():
        ids = None if citations is None else sorted({c["citationId"] for c in citations
                                                     if covers(c.get("source", {}), target)})
        inline = None if ids is None or answer is None else sorted(set(ids) & set(markers))
        slots[slot] = {"sourceCoverage": "UNKNOWN" if ids is None else "COVERED" if ids else "NOT_COVERED",
                       "sourceCitationIds": ids, "inlineEvidenceIds": inline,
                       "semanticVerdict": verdicts.get(slot, "UNKNOWN"),
                       "claimCitationBinding": "UNKNOWN"}
    return {"collectionVersion": SCHEMA_VERSION, "returnedCitations": safe_citations,
            "inlineCitationIds": None if answer is None else markers, "evidenceSlots": slots,
            "extraClaimsVerdict": "UNKNOWN"}

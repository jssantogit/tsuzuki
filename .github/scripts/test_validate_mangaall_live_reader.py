"""Transport-only regression tests; no synthetic manga candidates or Reader pixels."""
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name("validate_mangaall_live_reader.py")
spec = importlib.util.spec_from_file_location("mangaall_live_reader", path)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


def line(work, index, stage, decoded, viewer, pixels):
    return (
        "INSTRUMENTATION_STATUS: stream=MANGAALL_LIVE_ANDROID_READER"
        f"|case={validator.case_id(work)}|index={index}"
        "|mode=DIRECT_LIVE_ANDROID_READER_VIEW"
        f"|stage={stage}|catalog=RESOLVED|sqliteChapters=3|readerOptions=1"
        f"|pageMetadata=5|bitmapDecoded={str(decoded).lower()}"
        f"|readerViewLoaded={str(viewer).lower()}|windowPixelCopy={str(pixels).lower()}"
        "|activityNavigation=NOT_EXECUTED"
        "|pageErrorCode=NONE|pageErrorStage=NONE|pageHttpStatus=0"
    )


def batch(selected, pixels):
    return (
        "INSTRUMENTATION_STATUS: stream=MANGAALL_LIVE_ANDROID_READER_BATCH"
        f"|selected={selected}|rendered={pixels}|mode=DIRECT_LIVE_ANDROID_READER_VIEW"
    )


class RealReaderTransportTest(unittest.TestCase):
    def test_one_genuinely_reported_pixel_proof_is_allowed(self):
        user_input = "manga :: A Work\nmanhwa :: Another Work\n"
        report = validator.validate(
            line("A Work", 0, "RENDERED_PIXELS", True, True, True)
            + "\n" + batch(1, 1), user_input, 0, 2
        )
        self.assertEqual(report["assigned"], 1)
        self.assertEqual(report["rendered"], 1)
        self.assertNotIn("A Work", str(report))

    def test_failed_image_is_reported_not_treated_as_success(self):
        source = line("Some Work", 0, "IMAGE_HTTP_FAILED", False, False, False)
        report = validator.validate(source + "\n" + batch(1, 0), "Some Work", 0, 1)
        self.assertEqual(report["rendered"], 0)
        self.assertEqual(report["cases"][0]["stage"], "IMAGE_HTTP_FAILED")

    def test_pixel_boolean_without_view_ack_is_forgery(self):
        with self.assertRaises(ValueError):
            validator.validate(
                line("Work", 0, "RENDERED_PIXELS", True, False, True)
                + "\n" + batch(1, 1), "Work", 0, 1
            )

    def test_missing_shard_member_cannot_silently_disappear(self):
        with self.assertRaises(ValueError):
            validator.validate(
                line("Work A", 0, "RENDERED_PIXELS", True, True, True)
                + "\n" + batch(1, 1), "Work A\nWork B", 0, 1
            )

    def test_empty_shard_must_be_explicit_and_unique(self):
        report = validator.validate(batch(0, 0), "Work A", 1, 2)
        self.assertEqual(report["assigned"], 0)
        with self.assertRaises(ValueError):
            validator.validate("", "Work A", 1, 2)

    def test_duplicate_case_or_unexpected_facet_is_rejected(self):
        item = line("Work", 0, "PAGE_METADATA_ONLY", False, False, False)
        with self.assertRaises(ValueError):
            validator.validate(item + "\n" + item + "\n" + batch(1, 0), "Work", 0, 1)
        with self.assertRaises(ValueError):
            validator.validate(
                item.replace("mode=DIRECT_LIVE_ANDROID_READER_VIEW", "mode=FAKE_SUCCESS")
                + "\n" + batch(1, 0), "Work", 0, 1
            )

    def test_pages_http_status_requires_real_typed_source_error(self):
        item = line("Work", 0, "PROVIDER_PAGES_NOT_READY", False, False, False)
        valid = item.replace(
            "pageErrorCode=NONE|pageErrorStage=NONE|pageHttpStatus=0",
            "pageErrorCode=SOURCE_READ_ERROR|pageErrorStage=EXTERNAL_CHAPTER|pageHttpStatus=403"
        )
        report = validator.validate(valid + "\\n" + batch(1, 0), "Work", 0, 1)
        self.assertEqual(report["cases"][0]["pageHttpStatus"], 403)
        self.assertEqual(report["cases"][0]["pageErrorStage"], "EXTERNAL_CHAPTER")
        with self.assertRaises(ValueError):
            validator.validate(
                item.replace("pageHttpStatus=0", "pageHttpStatus=403")
                + "\\n" + batch(1, 0), "Work", 0, 1
            )

    def test_cannot_upgrade_metadata_only_to_rendered(self):
        item = line("Work", 0, "RENDERED_PIXELS", False, False, False)
        with self.assertRaises(ValueError):
            validator.validate(item + "\n" + batch(1, 0), "Work", 0, 1)


if __name__ == "__main__":
    unittest.main()

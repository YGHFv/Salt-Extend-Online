"""Offline tests for the class-file inspector; no application execution."""

import importlib.util
import pathlib
import struct
import unittest


spec = importlib.util.spec_from_file_location("salt_inspector", pathlib.Path(__file__).with_name("inspect-salt.py"))
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)


def minimal_class():
    def utf8(value):
        encoded = value.encode("utf-8")
        return b"\x01" + struct.pack(">H", len(encoded)) + encoded

    pool = utf8("example/\u015b") + b"\x07\x00\x01"
    pool += utf8("java/lang/Object") + b"\x07\x00\x03"
    pool += utf8("value") + utf8("Ljava/lang/String;")
    pool += utf8("<init>") + utf8("()V")
    header = b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 69, 9)
    body = struct.pack(">HHHH", 1, 2, 4, 0)
    body += struct.pack(">HHHHH", 1, 18, 5, 6, 0)
    body += struct.pack(">HHHHH", 1, 1, 7, 8, 0)
    body += b"\x00\x00"
    return header + pool + body


class ClassInspectorTests(unittest.TestCase):
    def test_member_metadata(self):
        result = inspector.class_metadata(minimal_class())
        self.assertEqual(result["name"], "example.\u015b")
        self.assertEqual(result["superclass"], "java.lang.Object")
        self.assertEqual(result["majorVersion"], 69)
        self.assertEqual(result["fields"][0]["descriptor"], "Ljava/lang/String;")
        self.assertEqual(result["methods"][0]["name"], "<init>")
        self.assertEqual(len(result["sha256"]), 64)

    def test_all_truncations_are_rejected(self):
        data = minimal_class()
        for end in range(len(data)):
            with self.subTest(end=end), self.assertRaises(ValueError):
                inspector.class_metadata(data[:end])

    def test_bad_magic(self):
        with self.assertRaisesRegex(ValueError, "Not a JVM class"):
            inspector.class_metadata(b"bad!" + minimal_class()[4:])

    def test_unsupported_pool_tag(self):
        data = bytearray(minimal_class())
        data[10] = 99
        with self.assertRaisesRegex(ValueError, "Unknown constant-pool tag"):
            inspector.class_metadata(data)

    def test_trailing_data(self):
        with self.assertRaisesRegex(ValueError, "trailing"):
            inspector.class_metadata(minimal_class() + b"x")

    def test_properties_escaping(self):
        self.assertEqual(inspector.property_escape("class.\u015b"), r"class.\u015b")
        self.assertEqual(inspector.property_escape("a=b:c"), r"a\=b\:c")


if __name__ == "__main__":
    unittest.main()

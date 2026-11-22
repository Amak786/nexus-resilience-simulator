"""Unit tests for the Zeek forwarder's parser (run: python -m unittest sensor/test_trace_zeek.py).

The log lines below follow Zeek's documented conn.log format; they are test fixtures, not observed traffic.
"""
import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location("trace_zeek", os.path.join(os.path.dirname(__file__), "trace-zeek.py"))
z = importlib.util.module_from_spec(spec)
spec.loader.exec_module(z)

TSV_HEADER = "#fields\tts\tuid\tid.orig_h\tid.orig_p\tid.resp_h\tid.resp_p\tproto\tservice\tduration\tconn_state\n"


class ParserTest(unittest.TestCase):
    def test_tsv(self):
        fields = z.header_fields(TSV_HEADER)
        row = z.parse_line("1795400000.123\tCx1\t192.168.1.20\t51515\t93.184.216.34\t443\ttcp\tssl\t0.5\tSF\n", fields)
        self.assertEqual(row["protocol"], "TCP")
        self.assertEqual(row["remoteAddress"], "93.184.216.34")
        self.assertEqual(row["remotePort"], 443)
        self.assertEqual(row["state"], "SF")
        self.assertTrue(row["observedAt"].endswith("Z"))

    def test_json(self):
        row = z.parse_line('{"ts":1795400000.5,"uid":"C2","id.orig_h":"10.0.0.5","id.orig_p":5353,"id.resp_h":"8.8.8.8",'
                           '"id.resp_p":53,"proto":"udp","conn_state":"S0"}', None)
        self.assertEqual(row["protocol"], "UDP")
        self.assertEqual(row["localAddress"], "10.0.0.5")

    def test_rejects(self):
        fields = z.header_fields(TSV_HEADER)
        self.assertIsNone(z.parse_line("#close\t2026-11-23\n", fields), "comment lines")
        self.assertIsNone(z.parse_line("1795400000\tC3\t10.0.0.1\t1\t127.0.0.1\t80\ttcp\t-\t-\tSF\n", fields), "loopback")
        self.assertIsNone(z.parse_line("1795400000\tC4\t10.0.0.1\t1\t8.8.8.8\t0\ticmp\t-\t-\tOTH\n", fields), "ICMP not ingested")
        self.assertIsNone(z.parse_line("broken line", fields), "wrong column count")
        self.assertIsNone(z.parse_line("{not json", None))
        self.assertIsNone(z.parse_line("1795400000\tC5\t10.0.0.1\t1\t8.8.8.8\t443\ttcp\n", None), "TSV without header")

    def test_no_payload_fields(self):
        row = z.parse_line('{"ts":1,"id.orig_h":"10.0.0.5","id.orig_p":1,"id.resp_h":"1.1.1.1","id.resp_p":443,"proto":"tcp",'
                           '"history":"ShADad","orig_bytes":512,"service":"ssl"}', None)
        self.assertEqual(set(row), {"observedAt", "protocol", "localAddress", "localPort", "remoteAddress", "remotePort", "state"})


if __name__ == "__main__":
    unittest.main()

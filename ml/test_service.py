"""Tests for the ML service, over real HTTP with stand-in models. Run: python -I ml/test_service.py"""
import http.client
import importlib.util
import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))  # -I leaves the script's folder off the path
spec = importlib.util.spec_from_file_location("service", Path(__file__).with_name("service.py"))
service = importlib.util.module_from_spec(spec)
sys.modules["service"] = service
spec.loader.exec_module(service)

import backends  # noqa: E402
import textguard  # noqa: E402


def fake(texts, kind):
    return [[float(len(t)), 1.0 if kind == "query" else 0.0] for t in texts]


def missing_model(texts, kind):
    raise ImportError("onnxruntime")


def no_weights(*args):
    raise backends.Unavailable("no voice")


def fake_tts(text, lang):
    return b"RIFF" + text.encode()


def fake_stt(audio, lang):
    return f"{lang}:{len(audio)}"


def fake_translate(texts, source, target, attempt=0):
    return [f"[{target}] {t}" for t in texts]


class Embed(unittest.TestCase):
    def test_one_vector_per_text(self):
        status, body = service.embed({"texts": ["ab", "c"], "kind": "query"}, fake)
        self.assertEqual(status, 200)
        self.assertEqual(body["vectors"], [[2.0, 1.0], [1.0, 1.0]])

    def test_bad_requests(self):
        for bad in ([], {"texts": []}, {"texts": [1]}, {"texts": ["a"], "kind": "x"}, {"texts": ["a"] * 257}):
            self.assertEqual(service.embed(bad, fake)[0], 400, bad)

    def test_without_a_model_it_refuses_rather_than_invents(self):
        status, body = service.embed({"texts": ["a"]}, missing_model)
        self.assertEqual(status, 503)
        self.assertNotIn("vectors", body)

    def test_e5_gets_its_prefixes(self):
        seen = []

        class Tokenizer:
            def encode_batch(self, texts):
                seen.extend(texts)
                raise StopIteration

        model = backends.E5Embedder()
        model.tokenizer, model.session, model.inputs = Tokenizer(), object(), set()
        for kind, prefix in (("query", "query: a"), ("passage", "passage: a")):
            try:
                model(["a"], kind)
            except (StopIteration, ImportError):
                pass
            if seen:  # numpy is not on every machine that runs these tests
                self.assertEqual(seen.pop(), prefix)


class Speech(unittest.TestCase):
    def test_tts(self):
        self.assertEqual(service.tts({"text": "नमस्ते", "lang": "hi"}, fake_tts)[0], 200)
        for bad in ([], {"text": "", "lang": "hi"}, {"text": "a", "lang": "fr"}, {"text": "a" * 2001, "lang": "hi"}):
            self.assertEqual(service.tts(bad, fake_tts)[0], 400, bad)
        self.assertEqual(service.tts({"text": "a", "lang": "hi"}, None)[0], 503)
        self.assertEqual(service.tts({"text": "a", "lang": "hi"}, no_weights)[0], 503)

    def test_stt(self):
        self.assertEqual(service.stt(b"abc", "hi", fake_stt), (200, {"text": "hi:3"}))
        self.assertEqual(service.stt(b"", "hi", fake_stt)[0], 400)
        self.assertEqual(service.stt(b"a", "xx", fake_stt)[0], 400)
        self.assertEqual(service.stt(b"a", "hi", None)[0], 503)

    def test_undecodable_audio_is_the_callers_fault(self):
        def broken(audio, lang):
            raise backends.BadInput("could not decode the audio")

        self.assertEqual(service.stt(b"a", "hi", broken)[0], 422)

    def test_voices_get_characters_they_know_with_blanks_between(self):
        self.assertEqual(backends.MmsTts.token_ids("Ab?", {"a": 5, "b": 6}), [0, 5, 0, 6, 0])


class Numbers(unittest.TestCase):
    def test_hindi_number_words(self):
        for value, words in ((6000, "छह हज़ार"), (250000, "दो लाख पचास हज़ार"), (125, "एक सौ पच्चीस"), (99, "निन्यानवे"), (10000000, "एक करोड़")):
            self.assertEqual(textguard.hindi_number(value), words)
        self.assertEqual(len(textguard._HI_0_99), 100)

    def test_speech_has_no_digits_or_symbols_left(self):
        spoken = textguard.for_speech("₹6,000 at 7.5% from https://pmkisan.gov.in/ for २ years", "hi", lambda v, l: textguard.hindi_number(v))
        self.assertEqual(spoken, "छह हज़ार रुपये at सात . पांच प्रतिशत from for दो years")

    def test_without_number_words_digits_are_read_one_by_one(self):
        self.assertEqual(textguard.for_speech("₹500", "ta"), "ஐந்து பூஜ்யம் பூஜ்யம் ரூபாய்")


class Guard(unittest.TestCase):
    def test_sentences(self):
        self.assertEqual(textguard.sentences("Rs. 500 is paid. Next one. हाँ। ठीक है।"), ["Rs. 500 is paid.", "Next one.", "हाँ।", "ठीक है।"])

    def test_numbers_compare_across_scripts_and_grouping(self):
        self.assertTrue(textguard.same_numbers("₹2,50,000 in 2026-27", "২৫০০০০ টাকা ২০২৬-২৭"))
        self.assertTrue(textguard.same_numbers("at https://a.in/2020.pdf", "यहाँ https://a.in/2020.pdf"))
        self.assertFalse(textguard.same_numbers("₹6,000 in three parts of ₹2,000", "৬ হাজার টাকা"))
        self.assertFalse(textguard.same_numbers("age 18", "आयु 19"))
        self.assertFalse(textguard.same_numbers("18 or 18", "18"))

    def test_protected_terms(self):
        self.assertEqual(
            textguard.protected_terms("Use PM-KISAN at a CSC after e-KYC, see https://pmkisan.gov.in/."),
            ["https://pmkisan.gov.in/", "PM-KISAN", "CSC", "e-KYC"])

    def test_masking_keeps_terms_out_of_the_models_way(self):
        masked, terms = textguard.mask("Use PM-KISAN at a CSC, see https://pmkisan.gov.in/.")
        self.assertEqual(masked, "Use <1> at a <2>, see <0>.")
        self.assertEqual(terms, ["https://pmkisan.gov.in/", "PM-KISAN", "CSC"])
        self.assertEqual(textguard.unmask("<0> <১> < 2 >", terms), "https://pmkisan.gov.in/ PM-KISAN CSC")
        self.assertIsNone(textguard.unmask("<0> <1>", terms))
        self.assertIsNone(textguard.unmask("<0> <1> <1> <2>", terms))

    def test_broken_output(self):
        self.assertTrue(textguard.looks_broken("Hello there", ""))
        self.assertTrue(textguard.looks_broken("Hello there", "Hello there"))
        self.assertTrue(textguard.looks_broken("short", "बहुत " * 20))
        self.assertFalse(textguard.looks_broken("Hello there", "नमस्ते"))


class Translate(unittest.TestCase):
    def test_sentence_by_sentence(self):
        status, body = service.translate({"texts": ["One. Two."], "target": "hi"}, fake_translate, "m")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"model": "m", "translations": ["[hi] One. [hi] Two."]})

    def test_a_changed_number_is_retried_then_refused(self):
        calls = []

        def wrong_then_right(texts, source, target, attempt=0):
            calls.append(attempt)
            return ["₹7,000 दिए जाते हैं" if attempt == 0 else "₹6,000 दिए जाते हैं" for _ in texts]

        self.assertEqual(service.translate({"texts": ["₹6,000 is paid"], "target": "hi"}, wrong_then_right)[1]["translations"], ["₹6,000 दिए जाते हैं"])
        self.assertEqual(calls, [0, 1])

        def always_wrong(texts, source, target, attempt=0):
            return ["₹7,000" for _ in texts]

        self.assertEqual(service.translate({"texts": ["₹6,000 is paid", "No numbers here"], "target": "hi"}, always_wrong)[1]["translations"], [None, None])

    def test_a_lost_acronym_is_refused(self):
        def transliterates(texts, source, target, attempt=0):
            return ["पीएम-किसान वेबसाइट" for _ in texts]

        self.assertEqual(service.translate({"texts": ["The PM-KISAN website"], "target": "hi"}, transliterates)[1]["translations"], [None])

        def keeps_marker(texts, source, target, attempt=0):
            return [t.replace("The", "यह").replace("website", "वेबसाइट") for t in texts]

        self.assertEqual(service.translate({"texts": ["The PM-KISAN website"], "target": "hi"}, keeps_marker)[1]["translations"], ["यह PM-KISAN वेबसाइट"])

    def test_bad_requests(self):
        for bad in ([], {"texts": ["a"]}, {"texts": ["a"], "target": "en"}, {"texts": ["a"], "target": "fr"}, {"texts": [""], "target": "hi"}):
            self.assertEqual(service.translate(bad, fake_translate)[0], 400, bad)
        self.assertEqual(service.translate({"texts": ["a"], "target": "hi"}, None)[0], 503)


class Http(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        models = service.Backends(
            embed=fake, tts=fake_tts, stt=fake_stt, translate=fake_translate,
            tts_languages=lambda: ["hi"], stt_languages=lambda: ["hi", "en"], models={"translate": "m"})
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), service.handler(models, "secret"))
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.port = cls.server.server_address[1]
        cls.url = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def post(self, token, path="/v1/embed", body=None):
        data = json.dumps({"texts": ["a"]}).encode() if body is None else body
        req = urllib.request.Request(self.url + path, data, {"Content-Type": "application/json"})
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        return urllib.request.urlopen(req)

    def test_health_needs_no_token(self):
        self.assertEqual(urllib.request.urlopen(self.url + "/healthz").status, 200)

    def test_token_is_required(self):
        self.assertEqual(json.load(self.post("secret"))["vectors"], [[1.0, 0.0]])
        for token in (None, "wrong"):
            with self.assertRaises(urllib.error.HTTPError) as e:
                self.post(token)
            self.assertEqual(e.exception.code, 401)

    def test_chunked_bodies_are_read(self):
        """The JDK client sends JSON chunked; this once made every Java call fail as bad JSON."""
        connection = http.client.HTTPConnection("127.0.0.1", self.port)
        connection.putrequest("POST", "/v1/embed")
        connection.putheader("Authorization", "Bearer secret")
        connection.putheader("Transfer-Encoding", "chunked")
        connection.endheaders()
        body = json.dumps({"texts": ["ab"]}).encode()
        connection.send(b"%x\r\n%s\r\n" % (5, body[:5]) + b"%x\r\n%s\r\n0\r\n\r\n" % (len(body) - 5, body[5:]))
        reply = connection.getresponse()
        self.assertEqual(reply.status, 200)
        self.assertEqual(json.load(reply)["vectors"], [[2.0, 0.0]])

    def test_status_lists_what_can_be_served(self):
        req = urllib.request.Request(self.url + "/v1/status", headers={"Authorization": "Bearer secret"})
        self.assertEqual(json.load(urllib.request.urlopen(req)), {"tts": ["hi"], "stt": ["hi", "en"], "models": {"translate": "m"}})

    def test_tts_returns_wav(self):
        reply = self.post("secret", "/v1/tts", json.dumps({"text": "a", "lang": "hi"}).encode())
        self.assertEqual(reply.headers["Content-Type"], "audio/wav")
        self.assertEqual(reply.read(), b"RIFFa")

    def test_stt_takes_the_raw_recording(self):
        self.assertEqual(json.load(self.post("secret", "/v1/stt?lang=hi", b"abcd")), {"text": "hi:4"})

    def test_translate(self):
        body = json.dumps({"texts": ["Hello"], "source": "en", "target": "ta"}).encode()
        self.assertEqual(json.load(self.post("secret", "/v1/translate", body))["translations"], ["[ta] Hello"])

    def test_oversized_bodies_are_refused(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.post("secret", "/v1/tts", b"x" * (service.MAX_BODY + 1))
        self.assertEqual(e.exception.code, 413)


if __name__ == "__main__":
    unittest.main()

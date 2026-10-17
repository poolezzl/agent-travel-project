import json
import unittest
from pathlib import Path

class S08ContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).parents[1]
        self.contract_text = (self.root / "contracts/openapi/s08-public.yaml").read_text(encoding="utf-8")

    def test_required_public_operations_are_declared(self):
        expected = {
            "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/me",
            "/v1/points/quotes", "/v1/points/reserve", "/v1/points/confirm", "/v1/points/refund",
            "/v1/points/grant", "/v1/points/balance",
        }
        for path in expected:
            self.assertIn(f"  {path}:", self.contract_text)
        self.assertIn("version: s08-v1", self.contract_text)

    def test_examples_match_declared_shapes(self):
        register = json.loads((self.root / "contracts/examples/s08/register-response.json").read_text(encoding="utf-8"))
        problem = json.loads((self.root / "contracts/examples/s08/problem-detail.json").read_text(encoding="utf-8"))
        self.assertEqual({"accessToken", "refreshToken", "expiresInSeconds", "userId", "role"}, set(register))
        self.assertEqual("USER", register["role"])
        self.assertEqual("INSUFFICIENT_POINTS", problem["code"])
        self.assertEqual(409, problem["status"])


if __name__ == "__main__":
    unittest.main()

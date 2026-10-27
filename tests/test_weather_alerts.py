"""Unit tests for threshold, rain-period grouping, and message formatting."""
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

from app import weather_alerts


class RainAlertsTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 28, 0, 0, tzinfo=timezone.utc)
        self.t0 = int(self.now.timestamp())

    def test_strictly_more_than_fifty_percent(self):
        payload = {
            "hourly": {
                "time": [self.t0, self.t0 + 3600, self.t0 + 7200,
                         self.t0 + 10800, self.t0 + 14400],
                "precipitation_probability": [50, 51, 73, 49, 90],
            }
        }
        with patch.object(weather_alerts, "THRESHOLD", 50):
            periods = weather_alerts.rain_periods(payload, self.now)
        self.assertEqual(periods, [
            (self.t0 + 3600, self.t0 + 10800, 73),
            (self.t0 + 14400, self.t0 + 18000, 90),
        ])

    def test_no_alert_at_exact_threshold(self):
        payload = {"hourly": {
            "time": [self.t0, self.t0 + 3600],
            "precipitation_probability": [50, 30],
        }}
        with patch.object(weather_alerts, "THRESHOLD", 50):
            self.assertEqual(weather_alerts.rain_periods(payload, self.now), [])

    def test_localized_message_contains_peak(self):
        message = weather_alerts.format_alert(
            "salamanca", (self.t0, self.t0 + 3600, 80)
        )
        self.assertIn("سالامانکا", message)
        self.assertIn("80", message)
        self.assertIn("Open-Meteo", message)


if __name__ == "__main__":
    unittest.main()

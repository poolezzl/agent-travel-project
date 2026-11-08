"""Real 和风天气 (QWeather) adapter and the weather cache, wired by ``wiring.py``.

The S10 backend ``RealQWeatherAdapter`` is a Java class with no REST/OpenAPI contract, so the
Python side calls QWeather HTTP directly and re-maps fields and error codes. Only allow-listed
fields are mapped; nothing is guessed. No key, raw response or private itinerary is logged.
"""

from app.agents.weather.adapters.cities import DEFAULT_QWEATHER_CITIES, CityRegistry
from app.agents.weather.adapters.qweather_adapter import QWeatherAdapter
from app.agents.weather.adapters.redis_weather_cache import RedisWeatherCache, WeatherStore

__all__ = [
    "DEFAULT_QWEATHER_CITIES",
    "CityRegistry",
    "QWeatherAdapter",
    "RedisWeatherCache",
    "WeatherStore",
]

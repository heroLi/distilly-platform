"""Explicit Chat Completions policy; never applied to image/audio APIs."""
import math
import os


class ProtocolConfigurationError(ValueError):
    failure_code = "MODEL_CONFIGURATION_ERROR"


class OutputLimitError(ValueError):
    failure_code = "MODEL_OUTPUT_LIMIT"


class OutputPolicy:
    def __init__(self, prefix="MODEL"):
        if prefix not in ("MODEL", "VISION"):
            raise ProtocolConfigurationError("invalid model policy prefix")
        self.parameter = os.getenv(prefix + "_OUTPUT_TOKEN_PARAMETER", os.getenv("MODEL_OUTPUT_TOKEN_PARAMETER", "max_tokens"))
        if self.parameter not in ("max_tokens", "max_completion_tokens"):
            raise ProtocolConfigurationError("invalid output token parameter")
        self.limits = {"default": self._integer(prefix + "_DEFAULT_OUTPUT_TOKENS", 4096),
                       "distill": self._integer("MODEL_DISTILL_OUTPUT_TOKENS", 4096),
                       "knowledge_answer": self._integer("MODEL_KNOWLEDGE_ANSWER_OUTPUT_TOKENS", 2048),
                       "persona_analysis": self._integer("MODEL_PERSONA_ANALYSIS_OUTPUT_TOKENS", 4096),
                       "persona_build": self._integer("MODEL_PERSONA_BUILD_OUTPUT_TOKENS", 8192)}
        raw = os.getenv(prefix + "_TEMPERATURE", os.getenv("MODEL_TEMPERATURE", "0"))
        try:
            self.temperature = None if raw == "omit" else float(raw)
            if self.temperature is not None and (not math.isfinite(self.temperature) or not 0 <= self.temperature <= 2):
                raise ValueError()
        except ValueError:
            raise ProtocolConfigurationError("invalid model temperature") from None
        self.json_mode = self._boolean(prefix, "JSON_MODE", "false")
        self.streaming = self._boolean(prefix, "STREAMING", "true")

    @staticmethod
    def _integer(name, default):
        try: value = int(os.getenv(name, str(default)))
        except ValueError: raise ProtocolConfigurationError("invalid " + name) from None
        if not 256 <= value <= 32768:
            raise ProtocolConfigurationError(name + " must be between 256 and 32768")
        return value

    @staticmethod
    def _boolean(prefix, field, default):
        value = os.getenv(prefix + "_" + field, os.getenv("MODEL_" + field, default)).lower()
        if value not in ("true", "false"):
            raise ProtocolConfigurationError("invalid model " + field)
        return value == "true"

    def limit(self, stage, explicit=None):
        if stage not in self.limits: raise ProtocolConfigurationError("invalid output stage")
        if explicit is None: return self.limits[stage]
        if type(explicit) is not int or not 256 <= explicit <= 32768:
            raise ProtocolConfigurationError("explicit output limit must be an integer between 256 and 32768")
        return explicit


def require_finish(reason):
    if reason == "length": raise OutputLimitError("model output or context limit reached")
    if reason != "stop": raise ValueError("model did not finish a text completion normally")

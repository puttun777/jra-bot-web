import pytest

from ml_train import logsetup


@pytest.fixture(autouse=True)
def _quiet_logs() -> None:
    import io

    logsetup.setup(stream=io.StringIO())

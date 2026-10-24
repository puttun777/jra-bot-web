import sys

from . import sigterm

sigterm.install()  # 重い import（numpy など）の前に。起動中の SIGTERM を取りこぼさない

from .cli import main  # noqa: E402

sys.exit(main())

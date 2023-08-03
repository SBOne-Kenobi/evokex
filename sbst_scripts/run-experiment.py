import argparse
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple, TextIO

from dataclass_csv import DataclassWriter

SCRIPT_DIR = Path(__file__).absolute().parent
EVOKEX_DIR = SCRIPT_DIR.parent


def init_evokex() -> str:
    result = Path(tempfile.gettempdir()) / 'light-weight-evokex' / 'evokex'
    if not result.exists():
        result.mkdir(parents=True)
        for path in [
            'runtool', 'kex.ini',
            'master/target/evosuite-master-1.1.0.jar',
            'junitcontest/target/evosuite-junitcontest-1.1.0.jar',
            'kex/runtime-deps/', 'sbst_loggers/'
        ]:
            src = EVOKEX_DIR / path
            dst = result / path
            if src.is_dir():
                shutil.copytree(src, dst, copy_function=shutil.copy)
            else:
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(src, dst)

    return str(result)


def init_tempdir() -> str:
    result = tempfile.mkdtemp(prefix='experiment-results-')
    Path(result).chmod(0o777)
    print(f'---- Experiment results will be in {result}')
    return result


def run_experiment(tool: str, benchmarks: str, output: str, runs: int, timeouts: List[int]):
    print('---- Run experiment')
    subprocess.run(
        ['./run-experiment.sh', tool, benchmarks, output, str(runs), *[str(i) for i in timeouts]],
        cwd=SCRIPT_DIR
    )
    print('---- Experiment finished')


@dataclass
class RawStatisticsItem:
    tool: str
    timeout: int
    benchmark: str
    run_number: int
    cut: str

    uncompilable: int
    broken: int
    failing: int

    lines_coverage: float
    branches_coverage: float
    mutants_coverage: float
    mutants_killed: float


def parse_key_value(f: TextIO) -> Tuple[str, str]:
    key, value = re.search(r'^([^:]+):\s(.+)$', f.readline().strip()).groups()
    return key, value


def skip(f: TextIO, n: int):
    for _ in range(n):
        f.readline()


def parse_raw_statistics_items(arr: List[RawStatisticsItem], file: Path):
    with open(file) as f:
        line = f.readline()
        while line:
            if not line.startswith(">>> RESULTS"):
                line = f.readline()
                continue

            _, tool = parse_key_value(f)
            tool, timeout = re.search(r'^(.+)-(\d+)$', tool).groups()
            _, bench = parse_key_value(f)
            _, cut = parse_key_value(f)
            _, run = parse_key_value(f)
            skip(f, 4)
            _, uncompilable = parse_key_value(f)
            _, broken = parse_key_value(f)
            _, failing = parse_key_value(f)
            skip(f, 2)
            _, lines_coverage = parse_key_value(f)
            skip(f, 2)
            _, branches_coverage = parse_key_value(f)
            skip(f, 2)
            _, mutants_coverage = parse_key_value(f)
            skip(f, 1)
            _, mutants_killed = parse_key_value(f)
            skip(f, 1)

            arr.append(RawStatisticsItem(
                tool, int(timeout), bench, int(run),
                cut, int(uncompilable), int(broken), int(failing),
                float(lines_coverage), float(branches_coverage),
                float(mutants_coverage), float(mutants_killed),
            ))

            line = f.readline()


def build_csv(results_dir: str, output: str):
    print('---- Run statistics collection')

    arr: List[RawStatisticsItem] = []

    stats_files = Path(results_dir).glob('**/metrics_log.txt')
    for file in stats_files:
        parse_raw_statistics_items(arr, file)

    with open(output, 'w', newline='') as f:
        w = DataclassWriter(f, arr, RawStatisticsItem)
        w.write()

    print('---- Statistics collection finished')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='Run big experiment using specified tool and project')
    parser.add_argument('--tool', help='path to tool (default: uses light-weight version of kexised-evosuite)')
    parser.add_argument('--benchmarks', required=True, help='path to benchmarks')
    parser.add_argument('--csv', default='output.csv', help='output .csv file (default: output.csv)')
    parser.add_argument('--output', help='path to run results (default: uses temporary directory)')
    parser.add_argument('--runs', default=1, type=int, help='runs number (default: 1)')
    parser.add_argument('--timeouts', required=True, nargs='+', type=int, help='list of time budget')
    return parser.parse_args()


def main():
    args = parse_args()

    output = args.output

    tool = args.tool
    if tool is None:
        tool = init_evokex()

    if output is None:
        output = init_tempdir()

    run_experiment(tool, args.benchmarks, output, args.runs, args.timeouts)

    build_csv(output, args.csv)


if __name__ == '__main__':
    main()

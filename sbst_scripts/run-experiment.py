import argparse
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple, TextIO

from dataclass_csv import DataclassWriter

SCRIPTS_DIR = Path(__file__).absolute().parent
PROJECT_DIR = SCRIPTS_DIR.parent
DEFAULT_TOOL_NAME = 'evokex-15-all'


def init_default_tool() -> str:
    result = Path(tempfile.gettempdir()) / 'default-tool' / DEFAULT_TOOL_NAME
    if not result.exists():
        result.mkdir(parents=True)
        for path in [
            'runtool', 'kex.ini',
            'master/target/evosuite-master-1.1.0.jar',
            'junitcontest/target/evosuite-junitcontest-1.1.0.jar',
            'kex/runtime-deps/', 'sbst_loggers/'
        ]:
            src = PROJECT_DIR / path
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
        cwd=SCRIPTS_DIR
    )
    print('---- Experiment finished')


@dataclass
class RawStatisticsItem:
    tool: str
    timeout: int
    benchmark: str
    run_number: int
    cut: str

    tests: int
    uncompilable: int
    broken: int
    failing: int

    lines_total: int
    lines_covered: int
    lines_coverage_ratio: float

    branches_total: int
    branches_covered: int
    branches_coverage_ratio: float

    mutants_total: int
    mutants_covered: int
    mutants_coverage_ratio: float

    mutants_killed: int
    mutants_killed_ratio: float


def parse_key_value(f: TextIO) -> Tuple[str, str]:
    key, value = re.search(r'^([^:]+):\s(.+)$', f.readline().strip()).groups()
    return key, value


def parse_n_values(f: TextIO, n: int) -> List[str]:
    return [parse_key_value(f)[1] for _ in range(n)]


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

            tool, bench, cut, run = parse_n_values(f, 4)
            tool, timeout = re.search(r'^(.+)-(\d+)$', tool).groups()

            skip(f, 3)

            tests, uncompilable, broken, failing, \
                lines_total, lines_covered, lines_coverage_ratio, \
                branches_total, branches_covered, branches_coverage_ratio, \
                mutants_total, mutants_covered, mutants_coverage_ratio, \
                mutants_killed, mutants_killed_ratio \
                = parse_n_values(f, 15)

            skip(f, 1)

            arr.append(RawStatisticsItem(
                tool, int(timeout), bench, int(run),
                cut, int(tests), int(uncompilable), int(broken), int(failing),
                int(lines_total), int(lines_covered), float(lines_coverage_ratio),
                int(branches_total), int(branches_covered), float(branches_coverage_ratio),
                int(mutants_total), int(mutants_covered), float(mutants_coverage_ratio),
                int(mutants_killed), float(mutants_killed_ratio)
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
    parser.add_argument('--tool', help='path to tool (default: creates default tool)')
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
        tool = init_default_tool()

    if output is None:
        output = init_tempdir()

    run_experiment(tool, args.benchmarks, output, args.runs, args.timeouts)

    build_csv(output, args.csv)


if __name__ == '__main__':
    main()

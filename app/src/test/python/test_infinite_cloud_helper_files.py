import errno
import importlib.util
import os
import pathlib
import sys
import tempfile
import types
import unittest
from unittest import mock


def load_helper():
    source = pathlib.Path(__file__).resolve().parents[2] / "main/res/raw/infinite_cloud_helper.py"
    spec = importlib.util.spec_from_file_location("tokenflow_file_test_helper", source)
    helper = importlib.util.module_from_spec(spec)
    with mock.patch.object(sys, "dont_write_bytecode", True):
        if os.name == "nt":
            # These tests exercise real path operations, not the POSIX task-locking functions.
            with mock.patch.dict(sys.modules, {"fcntl": types.ModuleType("fcntl")}):
                spec.loader.exec_module(helper)
        else:
            spec.loader.exec_module(helper)
    return helper


HELPER = load_helper()


class InfiniteCloudHelperFileTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="tokenflow-file-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name).resolve()

    def link(self, name, target, directory=False):
        link = self.root / name
        try:
            link.symlink_to(target, target_is_directory=directory)
        except OSError as error:
            if getattr(error, "winerror", None) == 1314 or error.errno in (errno.EPERM, errno.ENOSYS, errno.ENOTSUP):
                self.skipTest("Symbolic links are unavailable: %s" % error)
            raise
        return link

    def test_delete_directory_link_keeps_target_contents(self):
        target = self.root / "real-directory"
        target.mkdir()
        sentinel = target / "keep.txt"
        sentinel.write_text("keep", encoding="utf-8")
        link = self.link("directory-link", target, directory=True)

        result = HELPER.handle({"op": "delete", "path": str(link)})

        self.assertEqual(str(link), result["deleted"])
        self.assertFalse(link.is_symlink())
        self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))

    def test_delete_file_link_keeps_target(self):
        target = self.root / "keep.txt"
        target.write_text("keep", encoding="utf-8")
        link = self.link("file-link", target)

        HELPER.handle({"op": "delete", "path": str(link)})

        self.assertFalse(link.is_symlink())
        self.assertEqual("keep", target.read_text(encoding="utf-8"))

    def test_delete_dangling_link(self):
        target = self.root / "absent"
        link = self.link("dangling", target)

        HELPER.handle({"op": "delete", "path": str(link)})

        self.assertFalse(link.is_symlink())
        self.assertFalse(target.exists())

    def test_move_directory_link_moves_only_link(self):
        target = self.root / "real-directory"
        target.mkdir()
        sentinel = target / "keep.txt"
        sentinel.write_text("keep", encoding="utf-8")
        link = self.link("old-link", target, directory=True)
        destination = self.root / "new-link"

        HELPER.handle({"op": "move", "source": str(link), "target": str(destination)})

        self.assertFalse(link.is_symlink())
        self.assertTrue(destination.is_symlink())
        self.assertEqual(target, destination.resolve())
        self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))

    def test_move_dangling_link_preserves_missing_target(self):
        target = self.root / "absent"
        link = self.link("dangling", target)
        destination = self.root / "moved-link"

        HELPER.handle({"op": "move", "source": str(link), "target": str(destination)})

        self.assertFalse(link.is_symlink())
        self.assertTrue(destination.is_symlink())
        self.assertFalse(target.exists())

    def test_move_to_dangling_destination_does_not_follow_target(self):
        source = self.root / "source.txt"
        source.write_text("source", encoding="utf-8")
        missing_target = self.root / "must-stay-absent"
        destination = self.link("destination", missing_target)
        request = {"op": "move", "source": str(source), "target": str(destination)}

        if os.name == "nt":
            with self.assertRaises(FileExistsError):
                HELPER.handle(request)
            self.assertTrue(source.exists())
            self.assertTrue(destination.is_symlink())
        else:
            HELPER.handle(request)
            self.assertFalse(source.exists())
            self.assertFalse(destination.is_symlink())
            self.assertEqual("source", destination.read_text(encoding="utf-8"))
        self.assertFalse(missing_target.exists())

    @unittest.skipUnless(os.name == "posix", "POSIX rename replaces an existing destination entry")
    def test_move_replaces_destination_link_without_replacing_link_target(self):
        source = self.root / "source.txt"
        source.write_text("source", encoding="utf-8")
        target = self.root / "keep.txt"
        target.write_text("keep", encoding="utf-8")
        destination = self.link("destination", target)

        HELPER.handle({"op": "move", "source": str(source), "target": str(destination)})

        self.assertFalse(destination.is_symlink())
        self.assertEqual("source", destination.read_text(encoding="utf-8"))
        self.assertEqual("keep", target.read_text(encoding="utf-8"))

    def test_symlinked_parent_directory_is_still_supported(self):
        parent = self.root / "real-parent"
        parent.mkdir()
        source = parent / "remove.txt"
        source.write_text("remove", encoding="utf-8")
        parent_link = self.link("parent-link", parent, directory=True)

        HELPER.handle({"op": "delete", "path": str(parent_link / source.name)})

        self.assertFalse(source.exists())
        self.assertTrue(parent.is_dir())
        self.assertTrue(parent_link.is_symlink())

    def test_regular_file_move_and_recursive_directory_delete(self):
        parent = self.root / "directory"
        parent.mkdir()
        source = parent / "source.txt"
        source.write_text("source", encoding="utf-8")
        destination = parent / "renamed.txt"

        HELPER.handle({"op": "move", "source": str(source), "target": str(destination)})
        self.assertFalse(source.exists())
        self.assertEqual("source", destination.read_text(encoding="utf-8"))
        HELPER.handle({"op": "delete", "path": str(parent)})
        self.assertFalse(parent.exists())


if __name__ == "__main__":
    unittest.main()

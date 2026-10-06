"""Sélection d'un dossier sur le PC qui héberge le serveur."""
from __future__ import annotations

import ctypes
import sys
import threading
import uuid

_picker_lock = threading.Lock()
_CANCELLED = 0x800704C7
_FOLDER_OPTIONS = 0x20 | 0x40 | 0x800 | 0x8  # Dossier réel, existant ; ne change pas cwd.


class FolderPickerBusy(RuntimeError):
    pass


class _Guid(ctypes.Structure):
    _fields_ = [
        ("data1", ctypes.c_uint32), ("data2", ctypes.c_uint16),
        ("data3", ctypes.c_uint16), ("data4", ctypes.c_ubyte * 8),
    ]

    @classmethod
    def parse(cls, value: str) -> _Guid:
        return cls.from_buffer_copy(uuid.UUID(value).bytes_le)


def _check(result: int) -> None:
    if result & 0x80000000:
        raise OSError(f"Sélecteur Windows : HRESULT 0x{result & 0xffffffff:08x}")


def _method(pointer, index: int, result_type, *argument_types):
    table = ctypes.cast(pointer, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    prototype = ctypes.WINFUNCTYPE(result_type, ctypes.c_void_p, *argument_types)
    return prototype(table[index])


class _WindowsFolderDialog:
    """IFileOpenDialog, initialisé et libéré dans le thread de la requête."""

    def __init__(self):
        self.dialog = ctypes.c_void_p()
        self.initialized = False
        self.ole = ctypes.WinDLL("ole32")
        self.ole.CoInitializeEx.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        self.ole.CoInitializeEx.restype = ctypes.c_long
        self.ole.CoCreateInstance.argtypes = [
            ctypes.POINTER(_Guid), ctypes.c_void_p, ctypes.c_uint32,
            ctypes.POINTER(_Guid), ctypes.POINTER(ctypes.c_void_p),
        ]
        self.ole.CoCreateInstance.restype = ctypes.c_long
        self.ole.CoTaskMemFree.argtypes = [ctypes.c_void_p]
        self.ole.CoTaskMemFree.restype = None
        self.ole.CoUninitialize.argtypes = []
        self.ole.CoUninitialize.restype = None

    def __enter__(self):
        # STA et OLE1DDE désactivé ; le worker FastAPI n'a pas de boucle Tk.
        _check(self.ole.CoInitializeEx(None, 0x2 | 0x4))
        self.initialized = True
        try:
            clsid = _Guid.parse("dc1c5a9c-e88a-4dde-a5a1-60f82a20aef7")
            iid = _Guid.parse("d57c7288-d4ad-4768-be02-9d969532d960")
            _check(self.ole.CoCreateInstance(ctypes.byref(clsid), None, 1, ctypes.byref(iid), ctypes.byref(self.dialog)))
            options = ctypes.c_uint32()
            _check(_method(self.dialog, 10, ctypes.c_long, ctypes.POINTER(ctypes.c_uint32))(self.dialog, ctypes.byref(options)))
            _check(_method(self.dialog, 9, ctypes.c_long, ctypes.c_uint32)(self.dialog, options.value | _FOLDER_OPTIONS))
            _check(_method(self.dialog, 17, ctypes.c_long, ctypes.c_wchar_p)(self.dialog, "Choisir un dossier de médias"))
            return self
        except BaseException:
            self.close()
            raise

    def select(self) -> str:
        user = ctypes.WinDLL("user32")
        user.GetForegroundWindow.argtypes = []
        user.GetForegroundWindow.restype = ctypes.c_void_p
        result = _method(self.dialog, 3, ctypes.c_long, ctypes.c_void_p)(self.dialog, user.GetForegroundWindow())
        if result & 0xffffffff == _CANCELLED:
            return ""
        _check(result)
        item = ctypes.c_void_p()
        path = ctypes.c_void_p()
        try:
            _check(_method(self.dialog, 20, ctypes.c_long, ctypes.POINTER(ctypes.c_void_p))(self.dialog, ctypes.byref(item)))
            _check(_method(item, 5, ctypes.c_long, ctypes.c_uint32, ctypes.POINTER(ctypes.c_void_p))(item, 0x80058000, ctypes.byref(path)))
            return ctypes.wstring_at(path) if path.value else ""
        finally:
            if path.value:
                self.ole.CoTaskMemFree(path)
            if item.value:
                _method(item, 2, ctypes.c_uint32)(item)

    def close(self):
        try:
            if self.dialog.value:
                _method(self.dialog, 2, ctypes.c_uint32)(self.dialog)
                self.dialog.value = None
        finally:
            if self.initialized:
                self.ole.CoUninitialize()
                self.initialized = False

    def __exit__(self, *_):
        self.close()


def _choose_folder() -> str:
    if sys.platform == "win32":
        with _WindowsFolderDialog() as dialog:
            return dialog.select()
    # Le serveur peut aussi être lancé sur un poste Linux/macOS avec Tk installé.
    import tkinter as tk
    from tkinter import filedialog

    root = tk.Tk()
    try:
        root.withdraw()
        root.attributes("-topmost", True)
        return filedialog.askdirectory(title="Choisir un dossier de médias", mustexist=True) or ""
    finally:
        root.destroy()


def choose_media_folder() -> str:
    if not _picker_lock.acquire(blocking=False):
        raise FolderPickerBusy("Un sélecteur de dossiers est déjà ouvert.")
    try:
        return _choose_folder()
    finally:
        _picker_lock.release()

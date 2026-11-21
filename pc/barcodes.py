"""Barcode nutrition lookup. Product facts come from a named source, never a guess."""
from decimal import Decimal, InvalidOperation
import json
import re
import urllib.error
import urllib.request


def valid_barcode(code):
    if not isinstance(code, str) or not re.fullmatch(r"(?:\d{8}|\d{12,14})", code):
        return False
    total = sum(int(value) * (3 if i % 2 == 0 else 1) for i, value in enumerate(reversed(code[:-1])))
    return (10 - total % 10) % 10 == int(code[-1])


def lookup(code, opener=urllib.request.urlopen):
    if not valid_barcode(code):
        raise ValueError("Штрихкод не удалось прочитать надёжно. Ничего не записано.")
    url = f"https://world.openfoodfacts.org/api/v2/product/{code}?fields=code,product_name,product_name_ru,brands,nutriments"
    request = urllib.request.Request(url, headers={"User-Agent": "AndroidAgent/0.7 (https://github.com/Oleg-1703/android-agent)"})
    try:
        with opener(request, timeout=20) as response:
            body = response.read(1_000_001)
        if len(body) > 1_000_000:
            raise ValueError()
        product = json.loads(body)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            raise ValueError(f"По штрихкоду {code} точные КБЖУ не найдены. Ничего не записано.") from None
        raise ValueError("Сервис поиска по штрихкоду недоступен. Ничего не записано.") from None
    except (OSError, ValueError):
        raise ValueError("Не удалось проверить данные штрихкода. Ничего не записано.") from None
    p = product.get("product", {})
    if product.get("status") != 1 or p.get("code", code) != code:
        raise ValueError(f"По штрихкоду {code} точные КБЖУ не найдены. Ничего не записано.")
    nutrition = {"basis_grams": "100"}
    keys = {"calories": "energy-kcal_100g", "protein": "proteins_100g", "fat": "fat_100g", "carbs": "carbohydrates_100g"}
    try:
        for key, external in keys.items():
            value = Decimal(str(p.get("nutriments", {})[external]))
            if not value.is_finite() or not 0 <= value <= 10000:
                raise ValueError()
            nutrition[key] = format(value, "f")
    except (KeyError, ValueError, InvalidOperation):
        raise ValueError(f"Товар {code} найден, но полного КБЖУ нет. Ничего не записано.") from None
    name = p.get("product_name_ru") or p.get("product_name")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("У найденного штрихкода нет названия товара. Ничего не записано.")
    brand = p.get("brands", "")
    return {"query": (name + (" (" + brand + ")" if isinstance(brand, str) and brand else ""))[:150],
            "nutrition": nutrition, "source": "barcode", "source_url": f"https://world.openfoodfacts.org/product/{code}"}

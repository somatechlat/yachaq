"""
YACHAQ Platform SDK for Python
"""

from setuptools import setup, find_packages

setup(
    name="yachaq-sdk",
    version="1.0.0",
    description="YACHAQ Platform SDK for Python - Requester API",
    long_description=open("README.md").read() if __import__("os").path.exists("README.md") else "",
    long_description_content_type="text/markdown",
    author="YACHAQ Platform",
    author_email="sdk@yachaq.io",
    url="https://github.com/yachaq/yachaq-sdk-python",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    python_requires=">=3.9",
    install_requires=[
        "httpx>=0.25.0",
        "pydantic>=2.0.0",
    ],
    extras_require={
        "dev": [
            "pytest>=7.0.0",
            "pytest-asyncio>=0.21.0",
            "mypy>=1.0.0",
        ],
    },
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Topic :: Software Development :: Libraries :: Python Modules",
    ],
    keywords="yachaq data-sovereignty consent privacy sdk",
)

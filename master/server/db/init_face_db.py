"""
Initialize Face Recognition Database
PostgreSQL + pgvector
"""

from ..config import POSTGRES_DB_FACE, POSTGRES_HOST, POSTGRES_PASSWORD, POSTGRES_PORT, POSTGRES_USER
from ..face import get_face_store


def init_face_db():
    """Initialize face recognition database"""
    print(f"Initializing face recognition database: {POSTGRES_DB_FACE}")

    try:
        # Create database if not exists
        import psycopg
        conn = psycopg.connect(
            host=POSTGRES_HOST,
            port=POSTGRES_PORT,
            user=POSTGRES_USER,
            password=POSTGRES_PASSWORD,
            dbname="postgres"  # Connect to default db first
        )
        conn.autocommit = True
        conn.execute(f"CREATE DATABASE {POSTGRES_DB_FACE}")
        conn.close()
        print(f"Database {POSTGRES_DB_FACE} created")
    except psycopg.errors.DuplicateDatabase:
        print(f"Database {POSTGRES_DB_FACE} already exists")
    except Exception as e:
        print(f"Error creating database: {e}")

    # Initialize schema
    try:
        store = get_face_store()
        store.init_schema()
        print("Face recognition schema initialized successfully")
    except Exception as e:
        print(f"Error initializing schema: {e}")


if __name__ == "__main__":
    init_face_db()

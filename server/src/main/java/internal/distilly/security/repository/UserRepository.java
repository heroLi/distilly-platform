package internal.distilly.security.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcTemplate db;

    public UserRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int updateTokenHash(String tokenHash, String id) {
        return db.update("UPDATE app_user SET token_hash=? WHERE id=?", tokenHash, id);
    }

    public int insertUser(String id, String tokenHash) {
        return db.update("INSERT INTO app_user VALUES (?,?)", id, tokenHash);
    }

    public List<String> findUserIds() {
        return db.queryForList("SELECT id FROM app_user", String.class);
    }

    public List<String> findByTokenHash(String tokenHash) {
        return db.queryForList("SELECT id FROM app_user WHERE token_hash=?", String.class, tokenHash);
    }

}

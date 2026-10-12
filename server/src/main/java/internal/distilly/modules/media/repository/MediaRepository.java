package internal.distilly.modules.media.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MediaRepository {

    private final JdbcTemplate db;

    public MediaRepository(JdbcTemplate db) {
        this.db = db;
    }

    public int updateAvatar(Object avatar, String id) {
        return db.update("UPDATE persona SET avatar=? WHERE id=?", avatar, id);
    }

}

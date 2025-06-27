package br.com.tiinforma.backend.repositories;

import br.com.tiinforma.backend.domain.criador.Criador;
import br.com.tiinforma.backend.domain.usuario.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CriadorRepository extends JpaRepository<Criador, Long> {
    Optional<Criador> findByEmail(String email);

    Optional<Criador> findByCpf(String cpf);

    Optional<Criador> findByUsuarioId(Long usuarioId);

    List<Criador> findByStatusSolicitacao(String status);

    boolean existsByCpf(String cpf);
    boolean existsByEmailAndStatusSolicitacao(String email, String status);
    boolean existsByCpfAndIdNot(String cpf, Long id);

    boolean existsByUsuarioEmail(String email);
}

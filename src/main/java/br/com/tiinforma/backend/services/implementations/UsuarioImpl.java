package br.com.tiinforma.backend.services.implementations;

import br.com.tiinforma.backend.domain.criador.Criador;
import br.com.tiinforma.backend.domain.criador.CriadorCreateDto;
import br.com.tiinforma.backend.domain.enums.Funcao;
import br.com.tiinforma.backend.domain.userDetails.UserDetailsImpl;
import br.com.tiinforma.backend.domain.usuario.Usuario;
import br.com.tiinforma.backend.domain.usuario.administrador.AdministradorCreateDto;
import br.com.tiinforma.backend.domain.usuario.UsuarioCreateDto;
import br.com.tiinforma.backend.domain.usuario.UsuarioResponseDto;
import br.com.tiinforma.backend.domain.usuario.administrador.AdministradorResponseDto;
import br.com.tiinforma.backend.exceptions.ResourceNotFoundException;
import br.com.tiinforma.backend.repositories.CriadorRepository;
import br.com.tiinforma.backend.repositories.UsuarioRepository;
import br.com.tiinforma.backend.services.interfaces.UsuarioService;
import jakarta.persistence.EntityNotFoundException;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UsuarioImpl implements UsuarioService {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CriadorRepository criadorRepository;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public UsuarioResponseDto findById(Long id) {
        var usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + id));
        return modelMapper.map(usuario, UsuarioResponseDto.class);
    }

    @Override
    public List<UsuarioResponseDto> findAll() {
        return usuarioRepository.findAll()
                .stream()
                .map(usuario -> modelMapper.map(usuario, UsuarioResponseDto.class))
                .collect(Collectors.toList());
    }

    @Override
    public UsuarioResponseDto create(UsuarioCreateDto usuarioCreateDto) {
        validarEmailUnico(usuarioCreateDto.getEmail());
        var entity = modelMapper.map(usuarioCreateDto, Usuario.class);
        entity = usuarioRepository.save(entity);
        return modelMapper.map(entity, UsuarioResponseDto.class);
    }

    @Override
    public UsuarioResponseDto update(Long id, UsuarioCreateDto usuarioCreateDto) {
        var usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado"));

        usuario.setNome(usuarioCreateDto.getNome());
        usuario.setEmail(usuarioCreateDto.getEmail());
        usuario.setSenha(usuarioCreateDto.getSenha());
        usuario.setInteresses(usuarioCreateDto.getInteresses());

        var usuarioAtualizado = usuarioRepository.save(usuario);
        return modelMapper.map(usuarioAtualizado, UsuarioResponseDto.class);
    }

    @Override
    public void delete(Long id) {
        var usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + id));
        usuarioRepository.delete(usuario);
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public AdministradorResponseDto criarAdministrador(AdministradorCreateDto administradorCreateDto) {
        administradorCreateDto.setFuncao(Funcao.ADMINISTRADOR);
        validarEmailUnico(administradorCreateDto.getEmail());

        Usuario usuario = Usuario.builder()
                .nome(administradorCreateDto.getNome())
                .email(administradorCreateDto.getEmail())
                .senha(administradorCreateDto.getSenha())
                .funcao(Funcao.ADMINISTRADOR)
                .fotoUrl(administradorCreateDto.getFotoUrl())
                .build();

        usuario = usuarioRepository.save(usuario);
        return modelMapper.map(usuario, AdministradorResponseDto.class);
    }

    @Override
    public boolean existeCriadorComCpf(String cpf) {
        return criadorRepository.existsByCpf(cpf);
    }

    @Override
    public boolean existeSolicitacaoPendente(String email) {
        return criadorRepository.existsByEmailAndStatusSolicitacao(email, "PENDENTE");
    }

    @Override
    public ResponseEntity<?> solicitarCriador(CriadorCreateDto criadorDto, UserDetailsImpl userDetails) {
        try {
            if (criadorRepository.existsByEmailAndStatusSolicitacao(userDetails.getUsername(), "PENDENTE")) {
                return ResponseEntity.badRequest().body("Já existe uma solicitação pendente para este e-mail");
            }

            Usuario usuario = usuarioRepository.findByEmail(userDetails.getUsername())
                    .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado"));

            Criador solicitacao = Criador.builder()
                    .nome(criadorDto.getNome())
                    .email(userDetails.getUsername())
                    .cpf(criadorDto.getCpf())
                    .formacao(criadorDto.getFormacao())
                    .senha(passwordEncoder.encode(criadorDto.getSenha()))
                    .funcao(Funcao.USUARIO)
                    .statusSolicitacao("PENDENTE")
                    .usuario(usuario)
                    .build();

            criadorRepository.save(solicitacao);

            return ResponseEntity.ok("Solicitação para ser criador enviada com sucesso!");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao processar solicitação: " + e.getMessage());
        }
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<List<Map<String, Object>>> getSolicitacoesCriador() {
        List<Criador> solicitacoes = criadorRepository.findByStatusSolicitacao("PENDENTE");

        List<Map<String, Object>> response = solicitacoes.stream().map(criador -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", criador.getId());
            map.put("nome", criador.getNome());
            map.put("email", criador.getEmail());
            map.put("cpf", criador.getCpf());
            map.put("formacao", criador.getFormacao());
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<?> aprovarCriador(Long id) {
        Optional<Criador> criadorOpt = criadorRepository.findById(id);
        if (criadorOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Criador criador = criadorOpt.get();

        Optional<Usuario> usuarioOpt = usuarioRepository.findByEmail(criador.getEmail());
        if (usuarioOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Usuário não encontrado para o email: " + criador.getEmail());
        }

        Usuario usuario = usuarioOpt.get();

        usuario.setFuncao(Funcao.CRIADOR);
        usuario = usuarioRepository.save(usuario);

        criador.setUsuario(usuario);
        criador.setFuncao(Funcao.CRIADOR);
        criador.setStatusSolicitacao("APROVADO");
        criadorRepository.save(criador);

        return ResponseEntity.ok("Criador aprovado com sucesso!");
    }

    @Override
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<?> reprovarCriador(Long id) {
        Optional<Criador> criadorOpt = criadorRepository.findById(id);
        if (criadorOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        criadorRepository.delete(criadorOpt.get());
        return ResponseEntity.ok("Solicitação de criador reprovada");
    }

    private void validarEmailUnico(String email) {
        if (usuarioRepository.findByEmail(email).isPresent() ||
                criadorRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Já existe um usuário ou criador com este e-mail");
        }
    }

    @Override
    public void atualizarDescricao(Long id, String descricao) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + id));
        usuario.setDescricao(descricao);
        usuarioRepository.save(usuario);
    }

    public void atualizarFotoUrl(String email, String fotoUrl) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado"));

        usuario.setFotoUrl(fotoUrl);
        usuarioRepository.save(usuario);
    }

}